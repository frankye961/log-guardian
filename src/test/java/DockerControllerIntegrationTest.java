import com.logguardian.rest.DockerController;
import com.logguardian.rest.model.ContainerRulesetRequest;
import com.logguardian.rest.model.RuleEnum;
import com.logguardian.service.docker.DockerContainerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DockerControllerIntegrationTest {

    private DockerContainerService service;
    private WebTestClient client;

    @BeforeEach
    void setup() {
        service = mock(DockerContainerService.class);
        DockerController controller = new DockerController(service);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    void startTailing_returns200_andDelegatesToService() {
        ContainerRulesetRequest request = new ContainerRulesetRequest("abc123", null, RuleEnum.EQUAL);

        client.post()
                .uri("/api/tailing/start")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().is2xxSuccessful();

        verify(service).startTailing(request);
    }

    @Test
    void startTailing_returns400_whenServiceThrows() {
        ContainerRulesetRequest request = new ContainerRulesetRequest("abc123", null, RuleEnum.EQUAL);
        doThrow(new RuntimeException("boom")).when(service).startTailing(request);

        client.post()
                .uri("/api/tailing/start")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void stopTailing_returns200_andDelegatesToService() {
        ContainerRulesetRequest request = new ContainerRulesetRequest("abc123", null, RuleEnum.EQUAL);

        client.post()
                .uri("/api/tailing/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().is2xxSuccessful();

        verify(service).stopTrailing(request);
    }
}
