package com.logguardian.persistance;

import com.logguardian.persistance.interfaces.IncidentEventRepository;
import com.logguardian.persistance.interfaces.IncidentRepository;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentPersistenceTest {

    private final IncidentRepository incidentRepository = mock(IncidentRepository.class);
    private final IncidentEventRepository incidentEventRepository = mock(IncidentEventRepository.class);
    private final IncidentPersistence persistence = new IncidentPersistence(incidentRepository, incidentEventRepository);

    @Test
    void findsIncidentByFingerprintAndSourceId() {
        IncidentDocument incident = IncidentDocument.builder()
                .fingerprint("fp-timeout-123")
                .sourceId("container-42")
                .build();
        when(incidentRepository.findByFingerprintAndSourceId("fp-timeout-123", "container-42")).thenReturn(incident);

        IncidentDocument found = persistence.findIncident("fp-timeout-123", "container-42");

        assertThat(found).isSameAs(incident);
    }

    @Test
    void savesIncident() {
        IncidentDocument incident = IncidentDocument.builder()
                .fingerprint("fp-timeout-123")
                .sourceId("container-42")
                .build();
        when(incidentRepository.save(incident)).thenReturn(incident);

        IncidentDocument saved = persistence.saveIncident(incident);

        assertThat(saved).isSameAs(incident);
        verify(incidentRepository).save(incident);
    }

    @Test
    void savesAndQueriesIncidentEvents() {
        IncidentEventDocument event = IncidentEventDocument.builder()
                .incidentId("incident-1")
                .type(IncidentEventType.CREATED)
                .createdAt(Instant.parse("2026-03-24T12:00:00Z"))
                .build();

        when(incidentEventRepository.save(event)).thenReturn(event);
        when(incidentEventRepository.findAllByIncidentIdOrderByCreatedAtDesc("incident-1")).thenReturn(List.of(event));
        when(incidentEventRepository.findAllByIncidentIdAndTypeOrderByCreatedAtDesc("incident-1", IncidentEventType.CREATED))
                .thenReturn(List.of(event));
        when(incidentEventRepository.findTopByIncidentIdOrderByCreatedAtDesc("incident-1")).thenReturn(event);

        assertThat(persistence.saveIncidentEvent(event)).isSameAs(event);
        assertThat(persistence.findIncidentEvents("incident-1")).containsExactly(event);
        assertThat(persistence.findIncidentEventsByType("incident-1", IncidentEventType.CREATED)).containsExactly(event);
        assertThat(persistence.findLatestIncidentEvent("incident-1")).isSameAs(event);

        verify(incidentEventRepository).save(event);
        verify(incidentEventRepository).findAllByIncidentIdOrderByCreatedAtDesc("incident-1");
        verify(incidentEventRepository).findAllByIncidentIdAndTypeOrderByCreatedAtDesc("incident-1", IncidentEventType.CREATED);
        verify(incidentEventRepository).findTopByIncidentIdOrderByCreatedAtDesc("incident-1");
    }
}
