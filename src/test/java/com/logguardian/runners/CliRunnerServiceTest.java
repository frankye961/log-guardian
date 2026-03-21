package com.logguardian.runners;

import com.github.dockerjava.api.model.Container;
import com.logguardian.service.docker.DockerContainerService;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CliRunnerServiceTest {

    @Test
    void tailAllStartsEveryRunningContainerBeforeBlocking() throws Exception {
        DockerContainerService dockerContainerService = mock(DockerContainerService.class);
        TestCliRunnerService runner = new TestCliRunnerService(dockerContainerService);

        Container first = mock(Container.class);
        when(first.getId()).thenReturn("container-1");

        Container second = mock(Container.class);
        when(second.getId()).thenReturn("container-2");

        Disposable firstSubscription = mock(Disposable.class);
        Disposable secondSubscription = mock(Disposable.class);

        when(dockerContainerService.getRunningContainerList()).thenReturn(List.of(first, second));
        when(dockerContainerService.startStream("container-1")).thenReturn(firstSubscription);
        when(dockerContainerService.startStream("container-2")).thenReturn(secondSubscription);

        int exitCode = runner.execute(Command.TAIL_ALL);

        assertThat(exitCode).isZero();
        assertThat(runner.blockedSubscriptions).containsExactly(firstSubscription, secondSubscription);
        verify(dockerContainerService).startStream("container-1");
        verify(dockerContainerService).startStream("container-2");
    }

    @Test
    void tailOneWithoutContainerIdReturnsFailure() {
        DockerContainerService dockerContainerService = mock(DockerContainerService.class);
        TestCliRunnerService runner = new TestCliRunnerService(dockerContainerService);

        int exitCode = runner.execute(Command.TAIL_ONE);

        assertThat(exitCode).isEqualTo(1);
    }

    private static final class TestCliRunnerService extends CliRunnerService {
        private List<Disposable> blockedSubscriptions = List.of();

        private TestCliRunnerService(DockerContainerService dockerContainerService) {
            super(dockerContainerService);
        }

        @Override
        protected void blockUntilInterrupted(List<Disposable> subscriptions) {
            this.blockedSubscriptions = subscriptions;
        }

        @Override
        protected void exit(int code) {
        }
    }
}
