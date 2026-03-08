import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.logguardian.aggregator.MultilineAggregator;
import com.logguardian.ai.AiIncidentSummarizer;
import com.logguardian.fingerprint.anomaly.AnomalyDetector;
import com.logguardian.fingerprint.generator.FingerPrintGenerator;
import com.logguardian.fingerprint.window.FingerPrintWindowCounter;
import com.logguardian.parser.json.JsonParser;
import com.logguardian.parser.string.StringParser;
import com.logguardian.rest.model.ContainerRulesetRequest;
import com.logguardian.rest.model.RuleEnum;
import com.logguardian.service.docker.DockerContainerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DockerContainerServiceTest {

    private final DockerClient dockerClient = mock(DockerClient.class);
    private final MultilineAggregator aggregator = mock(MultilineAggregator.class);
    private final StringParser stringParser = mock(StringParser.class);
    private final JsonParser jsonParser = mock(JsonParser.class);
    private final FingerPrintGenerator fingerPrintGenerator = mock(FingerPrintGenerator.class);
    private final FingerPrintWindowCounter counter = mock(FingerPrintWindowCounter.class);
    private final AnomalyDetector detector = mock(AnomalyDetector.class);
    private final AiIncidentSummarizer summarizer = mock(AiIncidentSummarizer.class);

    @Test
    void startTailing_contains_acceptsShortId() {
        DockerContainerService svc = Mockito.spy(new DockerContainerService(
                dockerClient,
                aggregator,
                stringParser,
                jsonParser,
                fingerPrintGenerator,
                counter,
                detector,
                summarizer
        ));

        Container c1 = mock(Container.class);
        Mockito.when(c1.getId()).thenReturn("abc1234567890");

        doReturn(List.of(c1)).when(svc).getRunningContainerList();
        doReturn(Flux.never()).when(svc).streamLogs(anyString());

        ContainerRulesetRequest req = new ContainerRulesetRequest("abc123", null, RuleEnum.CONTAINS);
        svc.startTailing(req);

        verify(svc).streamLogs("abc1234567890");
    }

    @Test
    void startTailing_all_startsStreamsForEveryContainer() {
        DockerContainerService svc = Mockito.spy(new DockerContainerService(
                dockerClient,
                aggregator,
                stringParser,
                jsonParser,
                fingerPrintGenerator,
                counter,
                detector,
                summarizer
        ));

        Container c1 = mock(Container.class);
        Container c2 = mock(Container.class);
        Mockito.when(c1.getId()).thenReturn("container-1");
        Mockito.when(c2.getId()).thenReturn("container-2");

        doReturn(List.of(c1, c2)).when(svc).getRunningContainerList();
        doReturn(Flux.never()).when(svc).streamLogs(anyString());

        ContainerRulesetRequest req = new ContainerRulesetRequest("ignored", null, RuleEnum.ALL);
        svc.startTailing(req);

        verify(svc).streamLogs("container-1");
        verify(svc).streamLogs("container-2");
    }
}
