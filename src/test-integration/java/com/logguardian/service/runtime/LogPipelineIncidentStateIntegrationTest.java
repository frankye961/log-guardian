package com.logguardian.service.runtime;

import com.logguardian.aggregator.MultilineAggregator;
import com.logguardian.ai.AiIncidentSummarizer;
import com.logguardian.mapper.IncidentMapper;
import com.logguardian.parser.json.JsonParser;
import com.logguardian.parser.string.StringParser;
import com.logguardian.persistance.IncidentPersistence;
import com.logguardian.persistance.interfaces.IncidentEventRepository;
import com.logguardian.persistance.interfaces.IncidentRepository;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import com.logguardian.persistance.pojo.IncidentStatus;
import com.logguardian.service.email.EmailSenderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig
@ContextConfiguration(classes = LogPipelineIncidentStateIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "logguardian.ai.enabled=false",
        "logguardian.notifications.email.enabled=false"
})
class LogPipelineIncidentStateIntegrationTest {

    @Autowired
    private LogPipelineService service;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentEventRepository incidentEventRepository;

    @BeforeEach
    void resetMocks() {
        reset(incidentRepository, incidentEventRepository);
    }

    @Test
    void acknowledgePersistsAcknowledgedStateEvent() {
        IncidentDocument incident = incident("incident-1");
        when(incidentRepository.findById("incident-1")).thenReturn(Optional.of(incident));
        when(incidentRepository.save(incident)).thenAnswer(invocation -> invocation.getArgument(0));
        when(incidentEventRepository.save(any(IncidentEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.acknowledgeIncident("incident-1", "fp-timeout-123", "container-42");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(incident.getAcknowledgedAt()).isNotNull();
        verify(incidentRepository).save(incident);
        verify(incidentEventRepository).save(any(IncidentEventDocument.class));
    }

    @Test
    void resolvePersistsResolvedStateEvent() {
        IncidentDocument incident = incident("incident-2");
        when(incidentRepository.findById("incident-2")).thenReturn(Optional.of(incident));
        when(incidentRepository.save(incident)).thenAnswer(invocation -> invocation.getArgument(0));
        when(incidentEventRepository.save(any(IncidentEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.resolveIncident("incident-2");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolvedAt()).isNotNull();
        verify(incidentRepository).save(incident);
        verify(incidentEventRepository).save(any(IncidentEventDocument.class));
    }

    private static IncidentDocument incident(String incidentId) {
        return IncidentDocument.builder()
                .id(incidentId)
                .fingerprint("fp-timeout-123")
                .sourceId("container-42")
                .sourceName("payments-api")
                .status(IncidentStatus.OPEN)
                .lastSeenAt(Instant.parse("2026-04-02T12:00:00Z"))
                .lastWindowCount(57)
                .sampleMessage("Timeout while calling payments provider")
                .build();
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
        com.logguardian.fingerprint.generator.FingerPrintGenerator fingerPrintGenerator() {
            return mock(com.logguardian.fingerprint.generator.FingerPrintGenerator.class);
        }

        @Bean
        com.logguardian.fingerprint.window.FingerPrintWindowCounter fingerPrintWindowCounter() {
            return mock(com.logguardian.fingerprint.window.FingerPrintWindowCounter.class);
        }

        @Bean
        com.logguardian.fingerprint.anomaly.AnomalyDetector anomalyDetector() {
            return mock(com.logguardian.fingerprint.anomaly.AnomalyDetector.class);
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
