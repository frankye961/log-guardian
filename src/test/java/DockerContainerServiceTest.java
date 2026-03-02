import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.logguardian.rest.model.ContainerRulesetRequest;
import com.logguardian.rest.model.RuleEnum;
import com.logguardian.service.DockerContainerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.Mockito.*;

class DockerContainerServiceTest {

    @Test
    void startTailing_contains_acceptsShortId() {
        DockerClient dockerClient = mock(DockerClient.class);
        DockerContainerService svc = Mockito.spy(new DockerContainerService(dockerClient));

        Container c1 = mock(Container.class);
        when(c1.getId()).thenReturn("abc1234567890");

        doReturn(List.of(c1)).when(svc).getRunningContainerList();
        doReturn(Flux.never()).when(svc).streamLogs(anyString());

        ContainerRulesetRequest req = new ContainerRulesetRequest("abc", any(), RuleEnum.CONTAINS);
        req.setRule(RuleEnum.CONTAINS);
        req.setContainerId("abc123"); // short id

        svc.startTailing(req);

        verify(svc).streamLogs("abc1234567890");
    }
}