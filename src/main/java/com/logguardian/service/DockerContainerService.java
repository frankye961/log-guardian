package com.logguardian.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.logguardian.model.LogLine;
import com.logguardian.rest.model.ContainerRulesetRequest;
import com.logguardian.rest.model.RuleEnum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static com.logguardian.parser.mapper.LogLineMapper.map;
import static org.apache.hc.core5.io.Closer.closeQuietly;

@Slf4j
@Service
@AllArgsConstructor
public class DockerContainerService {

    private final DockerClient client;
    private final ConcurrentHashMap<String, Disposable> activeContainers = new ConcurrentHashMap<>();

    public List<Container> getRunningContainerList() {
        try {
            return getRunningContainers();
        } catch (Exception e) {
            log.error("Docker listContainers failed. Problem: ", e);
            throw new RuntimeException();
        }
    }

    public void startTailing(ContainerRulesetRequest request) {
        for (Container container : getRunningContainerList()) {
            if (checkRuleContainsOrEqual(request.getRule(), container.getId(), request.getContainerId())) {
                activeContainers.computeIfAbsent(container.getId(), this::startStream);
            }
        }
        if (request.getRule().equals(RuleEnum.ALL)) {
            tailAllActiveContainers();
        }

    }

    public void stopTrailing(ContainerRulesetRequest request) {
        var disposable = activeContainers.remove(request.getContainerId());
        if(disposable != null){
            disposable.dispose();
        }
        if(request.getRule().equals(RuleEnum.ALL)){
            stopAllTrailing();
        }
    }

    private void stopAllTrailing(){
        for (Container container : getRunningContainerList()){
            var disposable = activeContainers.remove(container.getId());
            if(disposable != null){
                disposable.dispose();
            }
        }
    }

    private boolean checkRuleContainsOrEqual(RuleEnum rule, String containerId, String id) {
        return switch (rule) {
            case CONTAINS -> containerId.contains(id);
            case EQUAL -> containerId.equals(id);
            case ALL -> false;
        };
    }

    private Disposable startStream(String containerId) {
        return streamLogs(containerId)
                .doOnError(e -> log.error(e.getMessage()))
                .doOnNext(logLine -> log.info(String.valueOf(logLine)))
                .doFinally(sig -> activeContainers.remove(containerId))
                .subscribe();
    }

    private void tailAllActiveContainers() {
        for (Container container : getRunningContainerList()) {
            activeContainers.computeIfAbsent(container.getId(), this::startStream);
        }
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
     * retrieves
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
            throw new RuntimeException();
        }
    }
}
