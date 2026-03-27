package com.logguardian.mapper;

import com.logguardian.ai.model.IncidentSeverity;
import com.logguardian.ai.model.IncidentSummary;
import com.logguardian.fingerprint.anomaly.AnomalyEvent;
import com.logguardian.parser.model.LogLevel;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import com.logguardian.persistance.pojo.IncidentStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentMapperTest {

    private final IncidentMapper mapper = Mappers.getMapper(IncidentMapper.class);

    @Test
    void mapsNewIncidentDocumentFromAnomalyAndSummary() {
        AnomalyEvent anomaly = anomaly();
        IncidentSummary summary = summary();

        IncidentDocument document = mapper.toIncidentDocument(anomaly, summary);

        assertThat(document.getId()).isNull();
        assertThat(document.getFingerprint()).isEqualTo("fp-timeout-123");
        assertThat(document.getSourceId()).isEqualTo("container-42");
        assertThat(document.getSourceName()).isEqualTo("payments-api");
        assertThat(document.getLogLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(document.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(document.getSeverity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(document.getFirstSeenAt()).isEqualTo(anomaly.detectedAt());
        assertThat(document.getLastSeenAt()).isEqualTo(anomaly.detectedAt());
        assertThat(document.getTotalOccurrences()).isEqualTo(57);
        assertThat(document.getLastWindowCount()).isEqualTo(57);
        assertThat(document.getSampleMessages()).containsExactly(anomaly.sampleMessage());
        assertThat(document.getAiTitle()).isEqualTo(summary.title());
    }

    @Test
    void updatesExistingIncidentDocumentAndTracksRegression() {
        IncidentDocument existing = IncidentDocument.builder()
                .fingerprint("fp-timeout-123")
                .sourceId("container-42")
                .sourceName("payments-api")
                .status(IncidentStatus.RESOLVED)
                .severity(IncidentSeverity.MEDIUM)
                .totalOccurrences(10)
                .lastWindowCount(10)
                .regressionCount(0)
                .sampleMessages(List.of("older sample"))
                .build();

        mapper.updateIncidentDocument(existing, anomaly(), summary());

        assertThat(existing.getStatus()).isEqualTo(IncidentStatus.REGRESSED);
        assertThat(existing.getRegressionCount()).isEqualTo(1);
        assertThat(existing.getResolvedAt()).isNull();
        assertThat(existing.getTotalOccurrences()).isEqualTo(67);
        assertThat(existing.getLastWindowCount()).isEqualTo(57);
        assertThat(existing.getSeverity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(existing.getSampleMessage()).isEqualTo(anomaly().sampleMessage());
        assertThat(existing.getSampleMessages()).contains(anomaly().sampleMessage());
    }

    @Test
    void mapsCreatedIncidentEventDocument() {
        IncidentEventDocument event = mapper.toCreatedIncidentEventDocument("incident-1", anomaly(), summary());

        assertThat(event.getIncidentId()).isEqualTo("incident-1");
        assertThat(event.getType()).isEqualTo(IncidentEventType.CREATED);
        assertThat(event.getCreatedAt()).isEqualTo(anomaly().detectedAt());
        assertThat(event.getFingerprint()).isEqualTo(anomaly().fingerprint());
        assertThat(event.getSeverity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(event.getTitle()).isEqualTo(summary().title());
    }

    @Test
    void mapsIncidentStateEventDocumentFromIncidentSnapshot() {
        IncidentDocument incident = IncidentDocument.builder()
                .id("incident-1")
                .fingerprint("fp-timeout-123")
                .sourceId("container-42")
                .sourceName("payments-api")
                .lastWindowCount(57)
                .severity(IncidentSeverity.HIGH)
                .aiTitle("Payment authorization timeout spike")
                .aiSummary("Repeated requests are timing out.")
                .probableCause("Upstream provider degraded.")
                .suggestedActions("Check dependency latency.")
                .sampleMessage("Timeout while calling payments provider")
                .build();

        IncidentEventDocument event = mapper.toIncidentStateEventDocument(
                incident,
                IncidentEventType.RESOLVED,
                "operator",
                "Resolved after provider recovery"
        );

        assertThat(event.getIncidentId()).isEqualTo("incident-1");
        assertThat(event.getType()).isEqualTo(IncidentEventType.RESOLVED);
        assertThat(event.getActor()).isEqualTo("operator");
        assertThat(event.getNote()).isEqualTo("Resolved after provider recovery");
        assertThat(event.getTitle()).isEqualTo("Payment authorization timeout spike");
    }

    private static AnomalyEvent anomaly() {
        return new AnomalyEvent(
                "fp-timeout-123",
                LogLevel.ERROR,
                "container-42",
                "payments-api",
                Instant.parse("2026-03-24T12:00:00Z"),
                57,
                "Timeout while calling payments provider"
        );
    }

    private static IncidentSummary summary() {
        return new IncidentSummary(
                "Payment authorization timeout spike",
                "Repeated payment authorization requests are timing out.",
                "The external payments provider is degraded.",
                "Check provider status and retry amplification.",
                IncidentSeverity.HIGH
        );
    }
}
