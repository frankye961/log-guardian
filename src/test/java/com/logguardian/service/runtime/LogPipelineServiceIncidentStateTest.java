package com.logguardian.service.runtime;

import com.logguardian.aggregator.MultilineAggregator;
import com.logguardian.ai.AiIncidentSummarizer;
import com.logguardian.mapper.IncidentMapper;
import com.logguardian.parser.json.JsonParser;
import com.logguardian.parser.string.StringParser;
import com.logguardian.persistance.IncidentPersistence;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import com.logguardian.persistance.pojo.IncidentStatus;
import com.logguardian.service.email.EmailSenderService;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogPipelineServiceIncidentStateTest {

    private final IncidentPersistence incidentPersistence = mock(IncidentPersistence.class);
    private final IncidentMapper incidentMapper = Mappers.getMapper(IncidentMapper.class);
    private final LogPipelineService service = new LogPipelineService(
            mock(MultilineAggregator.class),
            mock(StringParser.class),
            mock(JsonParser.class),
            mock(com.logguardian.fingerprint.generator.FingerPrintGenerator.class),
            mock(com.logguardian.fingerprint.window.FingerPrintWindowCounter.class),
            mock(com.logguardian.fingerprint.anomaly.AnomalyDetector.class),
            mock(AiIncidentSummarizer.class),
            mock(EmailSenderService.class),
            incidentPersistence,
            incidentMapper
    );

    @Test
    void acknowledgeUpdatesIncidentByIdAndPersistsEvent() {
        IncidentDocument incident = baseIncident();
        when(incidentPersistence.findIncidentById("incident-1")).thenReturn(Optional.of(incident));
        when(incidentPersistence.saveIncident(incident)).thenReturn(incident);
        when(incidentPersistence.saveIncidentEvent(any(IncidentEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.acknowledgeIncident("incident-1", "ignored-fingerprint", "ignored-source");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(incident.getAcknowledgedAt()).isNotNull();
        verify(incidentPersistence).saveIncident(incident);
        verify(incidentPersistence).saveIncidentEvent(any(IncidentEventDocument.class));
    }

    @Test
    void closeSuppressAndResolvePersistUpdatedIncidentStates() {
        IncidentDocument suppressIncident = baseIncident();
        IncidentDocument resolveIncident = baseIncident();
        IncidentDocument closeIncident = baseIncident();

        when(incidentPersistence.findIncidentById("incident-suppressed")).thenReturn(Optional.of(suppressIncident));
        when(incidentPersistence.findIncidentById("incident-resolved")).thenReturn(Optional.of(resolveIncident));
        when(incidentPersistence.findIncidentById("incident-closed")).thenReturn(Optional.of(closeIncident));
        when(incidentPersistence.saveIncident(any(IncidentDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(incidentPersistence.saveIncidentEvent(any(IncidentEventDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.suppressIncident("incident-suppressed");
        service.resolveIncident("incident-resolved");
        service.closeIncident("incident-closed");

        assertThat(suppressIncident.getStatus()).isEqualTo(IncidentStatus.SUPPRESSED);
        assertThat(resolveIncident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolveIncident.getResolvedAt()).isNotNull();
        assertThat(closeIncident.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(closeIncident.getResolvedAt()).isNotNull();
    }

    private static IncidentDocument baseIncident() {
        return IncidentDocument.builder()
                .id("incident-1")
                .fingerprint("fp-timeout-123")
                .sourceId("container-42")
                .sourceName("payments-api")
                .status(IncidentStatus.OPEN)
                .lastWindowCount(57)
                .sampleMessage("Timeout while calling payments provider")
                .build();
    }
}
