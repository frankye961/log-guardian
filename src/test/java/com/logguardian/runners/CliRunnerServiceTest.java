package com.logguardian.runners;

import com.logguardian.service.runtime.LogSource;
import com.logguardian.service.runtime.LogStreamingService;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CliRunnerServiceTest {

    @Test
    void tailAllStartsEveryRunningDockerSourceBeforeBlocking() throws Exception {
        LogStreamingService dockerService = mock(LogStreamingService.class);
        when(dockerService.runtimeKeys()).thenReturn(Set.of("docker"));
        TestCliRunnerService runner = new TestCliRunnerService(List.of(dockerService));

        Disposable firstSubscription = mock(Disposable.class);
        Disposable secondSubscription = mock(Disposable.class);

        when(dockerService.listRunningSources()).thenReturn(List.of(
                new LogSource("container-1", "api", "running"),
                new LogSource("container-2", "worker", "running")
        ));
        when(dockerService.startStream("container-1")).thenReturn(firstSubscription);
        when(dockerService.startStream("container-2")).thenReturn(secondSubscription);

        int exitCode = runner.execute("docker", Command.TAIL_ALL);

        assertThat(exitCode).isZero();
        assertThat(runner.blockedSubscriptions).containsExactly(firstSubscription, secondSubscription);
        verify(dockerService).startStream("container-1");
        verify(dockerService).startStream("container-2");
    }

    @Test
    void tailAllRunsInBackgroundWhenShellIsActive() {
        LogStreamingService dockerService = mock(LogStreamingService.class);
        when(dockerService.runtimeKeys()).thenReturn(Set.of("docker"));
        TestCliRunnerService runner = new TestCliRunnerService(List.of(dockerService));
        runner.shellSessionActive = true;

        Disposable firstSubscription = mock(Disposable.class);
        Disposable secondSubscription = mock(Disposable.class);

        when(dockerService.listRunningSources()).thenReturn(List.of(
                new LogSource("container-1", "api", "running"),
                new LogSource("container-2", "worker", "running")
        ));
        when(dockerService.startStream("container-1")).thenReturn(firstSubscription);
        when(dockerService.startStream("container-2")).thenReturn(secondSubscription);

        int exitCode = runner.execute("docker", Command.TAIL_ALL);

        assertThat(exitCode).isZero();
        assertThat(runner.blockedSubscriptions).isEmpty();
        assertThat(runner.backgroundedSubscriptions).containsExactly(firstSubscription, secondSubscription);
        verify(dockerService).startStream("container-1");
        verify(dockerService).startStream("container-2");
    }

    @Test
    void tailOneRunsInBackgroundWhenShellIsActive() {
        LogStreamingService dockerService = mock(LogStreamingService.class);
        when(dockerService.runtimeKeys()).thenReturn(Set.of("docker"));
        TestCliRunnerService runner = new TestCliRunnerService(List.of(dockerService));
        runner.shellSessionActive = true;

        Disposable subscription = mock(Disposable.class);
        when(dockerService.startStream("container-1")).thenReturn(subscription);

        int exitCode = runner.execute("docker", Command.TAIL_ONE, "container-1");

        assertThat(exitCode).isZero();
        assertThat(runner.blockedSubscriptions).isEmpty();
        assertThat(runner.backgroundedSubscriptions).containsExactly(subscription);
        verify(dockerService).startStream("container-1");
    }

    @Test
    void tailOneWithoutSourceIdReturnsFailure() {
        LogStreamingService dockerService = mock(LogStreamingService.class);
        when(dockerService.runtimeKeys()).thenReturn(Set.of("docker"));
        TestCliRunnerService runner = new TestCliRunnerService(List.of(dockerService));

        int exitCode = runner.execute("docker", Command.TAIL_ONE);

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    void missingRuntimeReturnsFailure() {
        LogStreamingService dockerService = mock(LogStreamingService.class);
        when(dockerService.runtimeKeys()).thenReturn(Set.of("docker"));
        TestCliRunnerService runner = new TestCliRunnerService(List.of(dockerService));

        int exitCode = runner.execute(Command.TAIL_ALL);

        assertThat(exitCode).isEqualTo(1);
    }

    private static final class TestCliRunnerService extends CliRunnerService {
        private List<Disposable> blockedSubscriptions = List.of();
        private List<Disposable> backgroundedSubscriptions = List.of();
        private boolean shellSessionActive;

        private TestCliRunnerService(List<LogStreamingService> services) {
            super(services);
        }

        @Override
        protected void blockUntilInterrupted(List<Disposable> subscriptions) {
            this.blockedSubscriptions = subscriptions;
        }

        @Override
        protected boolean isShellSessionActive() {
            return shellSessionActive;
        }

        @Override
        protected void registerBackgroundJob(String description, List<Disposable> subscriptions) {
            this.backgroundedSubscriptions = subscriptions;
        }

        @Override
        protected void exit(int code) {
        }
    }
}
