/*
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.logguardian.aggregator.MultilineAggregator;
import com.logguardian.parser.json.JsonParser;
import com.logguardian.parser.string.StringParser;
import com.logguardian.rest.model.ContainerRulesetRequest;
import com.logguardian.rest.model.RuleEnum;
import com.logguardian.service.docker.DockerContainerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.Mockito.*;

class DockerContainerServiceTest {

    private final DockerClient dockerClient = mock(DockerClient.class);
    private final MultilineAggregator aggregator = mock(MultilineAggregator.class);
    private final StringParser stringParser = mock(StringParser.class);
    private final JsonParser jsonParser = mock(JsonParser.class);

    @Test
    void startTailing_contains_acceptsShortId() {

        DockerContainerService svc = Mockito.spy(new DockerContainerService(dockerClient, aggregator, stringParser, jsonParser));

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
}*/
