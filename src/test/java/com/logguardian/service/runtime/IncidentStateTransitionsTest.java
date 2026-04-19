package com.logguardian.service.runtime;

import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import com.logguardian.persistance.pojo.IncidentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentStateTransitionsTest {

    @Test
    void acknowledgeSetsStatusAndOnlyInitialAcknowledgedAt() {
        Instant initialAcknowledgedAt = Instant.parse("2026-04-02T10:00:00Z");
        IncidentDocument incident = IncidentDocument.builder()
                .status(IncidentStatus.OPEN)
                .acknowledgedAt(initialAcknowledgedAt)
                .build();

        IncidentStateTransitions.apply(
                incident,
                IncidentEventType.ACKNOWLEDGED,
                Instant.parse("2026-04-02T12:00:00Z")
        );

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(incident.getAcknowledgedAt()).isEqualTo(initialAcknowledgedAt);
    }

    @Test
    void resolveAndCloseSetResolvedAtWhileRegressedClearsIt() {
        Instant now = Instant.parse("2026-04-02T12:00:00Z");
        IncidentDocument incident = IncidentDocument.builder()
                .status(IncidentStatus.OPEN)
                .build();

        IncidentStateTransitions.apply(incident, IncidentEventType.RESOLVED, now);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getResolvedAt()).isEqualTo(now);

        IncidentStateTransitions.apply(incident, IncidentEventType.REGRESSED, now.plusSeconds(60));
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.REGRESSED);
        assertThat(incident.getResolvedAt()).isNull();

        IncidentStateTransitions.apply(incident, IncidentEventType.CLOSED, now.plusSeconds(120));
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(incident.getResolvedAt()).isEqualTo(now.plusSeconds(120));
    }

    @Test
    void suppressSetsSuppressedStatus() {
        IncidentDocument incident = IncidentDocument.builder()
                .status(IncidentStatus.OPEN)
                .build();

        IncidentStateTransitions.apply(
                incident,
                IncidentEventType.SUPPRESSED,
                Instant.parse("2026-04-02T12:00:00Z")
        );

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.SUPPRESSED);
    }
}
