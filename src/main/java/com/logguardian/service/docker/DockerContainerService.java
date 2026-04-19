package com.logguardian.service.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.logguardian.model.LogLine;
import com.logguardian.service.runtime.LogPipelineService;
import com.logguardian.service.runtime.LogSource;
import com.logguardian.service.runtime.LogStreamingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static com.logguardian.mapper.LogLineMapper.map;
import static org.apache.hc.core5.io.Closer.closeQuietly;

@Slf4j
@Service
public class DockerContainerService implements LogStreamingService {

    private final DockerClient client;
    private final LogPipelineService logPipelineService;
    private final ConcurrentMap<String, Disposable> activeContainers = new ConcurrentHashMap<>();

    public DockerContainerService(DockerClient client, LogPipelineService logPipelineService) {
        this.client = client;
        this.logPipelineService = logPipelineService;
    }

    @Override
    public Set<String> runtimeKeys() {
        return Set.of("docker");
    }

    @Override
    public List<LogSource> listRunningSources() {
        try {
            return getRunningContainers().stream()
                    .map(this::toSource)
                    .toList();
        } catch (Exception e) {
            log.error("Docker listContainers failed. Problem: ", e);
            throw new IllegalStateException("Failed to list running Docker containers", e);
        }
    }


    @Override
    public Disposable startStream(String containerId) {
        return activeContainers.compute(containerId, (id, currentStream) -> {
            if (currentStream != null && !currentStream.isDisposed()) {
                log.info("Stream already active for container {}", containerId);
                return currentStream;
            }

            AtomicReference<Disposable> createdSubscription = new AtomicReference<>();
            Disposable subscription = logPipelineService.process(containerId, streamLogs(containerId))
                    .doOnError(error -> log.error("Stream failed for container {}", containerId, error))
                    .doFinally(signal -> activeContainers.computeIfPresent(containerId, (key, active) ->
                            active == createdSubscription.get() ? null : active))
                    .subscribe();
            createdSubscription.set(subscription);
            return subscription;
        });
    }

    /**
     * streams logs from a container
     *
     * @param containerId
     * @return
     */
    public Flux<LogLine> streamLogs(String containerId) {
        log.info("stream started...");
        return Flux.create(line -> {
            ResultCallback<Frame> callback = new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    line.next(map(frame, containerId));
                }

                @Override
                public void onError(Throwable t) {
                    line.error(t);
                }

                @Override
                public void onComplete() {
                    line.complete();
                }
            };
            attachContainerCmd(containerId, callback);
            line.onCancel(() -> closeQuietly(callback));
            line.onDispose(() -> closeQuietly(callback));
        });
    }

    /**
     * retrieves all the running containers
     *
     * @return
     */
    private List<Container> getRunningContainers() {
        return client.
                listContainersCmd().
                exec();
    }

    private void attachContainerCmd(String containerId, ResultCallback<Frame> callback) {
        try {
            client.logContainerCmd(containerId).
                    withStdErr(true)
                    .withStdOut(true)
                    .withFollowStream(true)
                    .withTailAll().exec(callback);
        } catch (Exception e) {
            log.error("Error in attaching to the container: ", e);
            throw new IllegalStateException("Failed to attach to container " + containerId, e);
        }
    }

    @Override
    public void stopAllStreams() {
        activeContainers.values().forEach(Disposable::dispose);
        activeContainers.clear();
    }

    @Override
    public void acknowledgeIncident(String incidentId, String fingerprint, String sourceId) {
        logPipelineService.acknowledgeIncident(incidentId, fingerprint, sourceId);
    }


    @Override
    public void closeIncident(String incidentId) {
        logPipelineService.closeIncident(incidentId);
    }

    @Override
    public void suppressIncident(String incidentId) {
        logPipelineService.suppressIncident(incidentId);
    }

    @Override
    public void resolveIncident(String incidentId) {
        logPipelineService.resolveIncident(incidentId);
    }

    private LogSource toSource(Container container) {
        return new LogSource(
                safe(container.getId()),
                extractName(container),
                safe(container.getStatus())
        );
    }

    private String extractName(Container container) {
        if (container.getNames() == null || container.getNames().length == 0) {
            return "unknown";
        }
        return container.getNames()[0];
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
