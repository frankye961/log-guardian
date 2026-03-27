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
import com.logguardian.persistance.pojo.IncidentStatus;
import com.logguardian.service.email.EmailSenderService;
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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@ContextConfiguration(classes = LogPipelinePersistenceIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "logguardian.ai.enabled=true",
        "logguardian.notifications.email.enabled=false"
})
class LogPipelinePersistenceIntegrationTest {

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

    @Test
    void updatesExistingIncidentWhenSameFingerprintAndSourceReappears() {
        Instant now = Instant.parse("2026-03-24T15:00:00Z");
        LogEntry aggregatedEntry = new LogEntry("container-42", now, "2026-03-24 15:00:00 ERROR timeout");
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
                "Repeated payment authorization requests are timing out.",
                "The external payments provider is degraded.",
                "Check provider status and retry amplification.",
                IncidentSeverity.HIGH
        );
        IncidentDocument existing = IncidentDocument.builder()
                .id("incident-1")
                .fingerprint("fp-timeout-123")
                .sourceId("container-42")
                .sourceName("payments-api")
                .status(IncidentStatus.RESOLVED)
                .severity(IncidentSeverity.MEDIUM)
                .totalOccurrences(10)
                .lastWindowCount(10)
                .regressionCount(0)
                .build();

        when(aggregator.transform(any())).thenReturn(Flux.just(aggregatedEntry));
        when(stringParser.parse(aggregatedEntry)).thenReturn(parsedEvent);
        when(fingerPrintGenerator.generateFingerprint(parsedEvent)).thenReturn(parsedEvent);
        when(counter.countFingerprint(parsedEvent)).thenReturn(57);
        when(detector.detectAnomaly(any())).thenReturn(Optional.of(anomaly));
        when(summarizer.summarize(any())).thenReturn(summary);
        when(incidentRepository.findByFingerprintAndSourceId("fp-timeout-123", "container-42")).thenReturn(existing);
        when(incidentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StepVerifier.create(service.process(
                        "container-42",
                        Flux.just(LogLine.builder()
                                .containerId("container-42")
                                .receivedAt(now)
                                .line("2026-03-24 15:00:00 ERROR timeout")
                                .build())
                ))
                .expectNext(summary)
                .verifyComplete();

        assertThat(existing.getStatus()).isEqualTo(IncidentStatus.REGRESSED);
        assertThat(existing.getRegressionCount()).isEqualTo(1);
        assertThat(existing.getTotalOccurrences()).isEqualTo(67);
        assertThat(existing.getLastWindowCount()).isEqualTo(57);
        assertThat(existing.getSeverity()).isEqualTo(IncidentSeverity.HIGH);
        verify(incidentRepository).findByFingerprintAndSourceId("fp-timeout-123", "container-42");
        verify(incidentRepository, times(1)).save(eq(existing));
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
