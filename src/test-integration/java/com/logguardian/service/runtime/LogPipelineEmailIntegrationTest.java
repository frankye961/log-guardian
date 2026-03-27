package com.logguardian.service.runtime;

import com.logguardian.aggregator.MultilineAggregator;
import com.logguardian.ai.AiIncidentSummarizer;
import com.logguardian.ai.model.IncidentSeverity;
import com.logguardian.ai.model.IncidentSummary;
import com.logguardian.fingerprint.anomaly.AnomalyDetector;
import com.logguardian.fingerprint.anomaly.AnomalyEvent;
import com.logguardian.fingerprint.generator.FingerPrintGenerator;
import com.logguardian.fingerprint.window.FingerPrintWindowCounter;
import com.logguardian.mapper.IncidentMapper;
import com.logguardian.model.LogEntry;
import com.logguardian.model.LogLine;
import com.logguardian.parser.json.JsonParser;
import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.model.LogLevel;
import com.logguardian.parser.string.StringParser;
import com.logguardian.persistance.IncidentPersistence;
import com.logguardian.persistance.interfaces.IncidentEventRepository;
import com.logguardian.persistance.interfaces.IncidentRepository;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.service.email.EmailSenderService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@ContextConfiguration(classes = LogPipelineEmailIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "logguardian.ai.enabled=true",
        "logguardian.notifications.email.enabled=true",
        "logguardian.notifications.email.from=alerts@logguardian.dev",
        "logguardian.notifications.email.reply-to=reply@logguardian.dev",
        "logguardian.notifications.email.to=ops@logguardian.dev,admin@logguardian.dev",
        "logguardian.notifications.email.subject-prefix=[Guardian]"
})
class LogPipelineEmailIntegrationTest {

    @Autowired
    private LogPipelineService service;

    @Autowired
    private MultilineAggregator aggregator;

    @Autowired
    private StringParser stringParser;

    @Autowired
    private FingerPrintGenerator fingerPrintGenerator;

    @Autowired
    private FingerPrintWindowCounter counter;

    @Autowired
    private AnomalyDetector detector;

    @Autowired
    private AiIncidentSummarizer summarizer;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private TestCapturingMailSender mailSender;

    @BeforeEach
    void setUp() {
        mailSender.clear();
    }

    @Test
    void processesAnomalyPersistsIncidentAndSendsReadableIncidentEmail() throws Exception {
        Instant now = Instant.parse("2026-03-22T15:00:00Z");
        LogEntry aggregatedEntry = new LogEntry("container-42", now, "2026-03-22 15:00:00 ERROR timeout");
        LogEvent parsedEvent = new LogEvent(
                "DOCKER",
                "container-42",
                "payments-api",
                now,
                now,
                LogLevel.ERROR,
                "Timeout while calling payments provider",
                "fp-timeout-123",
                Map.of()
        );
        AnomalyEvent anomaly = new AnomalyEvent(
                "fp-timeout-123",
                LogLevel.ERROR,
                "container-42",
                "payments-api",
                now,
                57,
                "Timeout while calling payments provider"
        );
        IncidentSummary summary = new IncidentSummary(
                "Payment authorization timeout spike",
                "Repeated payment authorization requests are timing out and causing customer-facing errors.",
                "The external payments provider is degraded or the service is exhausting outbound resources.",
                "Check provider status, recent deployments, thread pool saturation, and retry amplification.",
                IncidentSeverity.HIGH
        );

        when(aggregator.transform(any())).thenReturn(Flux.just(aggregatedEntry));
        when(stringParser.parse(aggregatedEntry)).thenReturn(parsedEvent);
        when(fingerPrintGenerator.generateFingerprint(parsedEvent)).thenReturn(parsedEvent);
        when(counter.countFingerprint(parsedEvent)).thenReturn(57);
        when(detector.detectAnomaly(any())).thenReturn(Optional.of(anomaly));
        when(summarizer.summarize(any())).thenReturn(summary);
        when(incidentRepository.findByFingerprintAndSourceId("fp-timeout-123", "container-42")).thenReturn(null);
        when(incidentRepository.save(any())).thenAnswer(invocation -> {
            IncidentDocument incident = invocation.getArgument(0);
            incident.setId("incident-1");
            return incident;
        });

        Flux<IncidentSummary> result = service.process(
                "container-42",
                Flux.just(LogLine.builder()
                        .containerId("container-42")
                        .receivedAt(now)
                        .line("2026-03-22 15:00:00 ERROR timeout")
                        .build())
        );

        StepVerifier.create(result)
                .expectNext(summary)
                .verifyComplete();

        assertThat(mailSender.sentMessages()).hasSize(1);
        MimeMessage message = mailSender.sentMessages().getFirst();
        assertThat(message.getSubject()).isEqualTo("[Guardian] [HIGH] ERROR anomaly in payments-api");
        assertThat(message.getAllRecipients()).extracting(address -> address.toString())
                .containsExactly("ops@logguardian.dev", "admin@logguardian.dev");

        String rawMessage = serialize(message);
        assertThat(rawMessage).contains("Payment authorization timeout spike");
        assertThat(rawMessage).contains("Repeated payment authorization requests are timing out");
        assertThat(rawMessage).contains("fp-timeout-123");
        assertThat(rawMessage).contains("Recommended Investigation Steps");
        assertThat(rawMessage).contains("Timeout while calling payments provider");
    }

    private static String serialize(MimeMessage message) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            message.writeTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new AssertionError("Failed to serialize MimeMessage", exception);
        }
    }

    @Import({LogPipelineService.class, EmailSenderService.class, IncidentPersistence.class})
    static class TestConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        MultilineAggregator multilineAggregator() {
            return mock(MultilineAggregator.class);
        }

        @Bean
        StringParser stringParser() {
            return mock(StringParser.class);
        }

        @Bean
        JsonParser jsonParser() {
            return mock(JsonParser.class);
        }

        @Bean
        FingerPrintGenerator fingerPrintGenerator() {
            return mock(FingerPrintGenerator.class);
        }

        @Bean
        FingerPrintWindowCounter fingerPrintWindowCounter() {
            return mock(FingerPrintWindowCounter.class);
        }

        @Bean
        AnomalyDetector anomalyDetector() {
            return mock(AnomalyDetector.class);
        }

        @Bean
        AiIncidentSummarizer aiIncidentSummarizer() {
            return mock(AiIncidentSummarizer.class);
        }

        @Bean
        IncidentRepository incidentRepository() {
            return mock(IncidentRepository.class);
        }

        @Bean
        IncidentEventRepository incidentEventRepository() {
            return mock(IncidentEventRepository.class);
        }

        @Bean
        IncidentMapper incidentMapper() {
            return Mappers.getMapper(IncidentMapper.class);
        }

        @Bean
        TestCapturingMailSender javaMailSender() {
            return new TestCapturingMailSender();
        }
    }
}
