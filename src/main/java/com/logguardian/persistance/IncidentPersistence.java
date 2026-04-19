package com.logguardian.persistance;

import com.logguardian.persistance.interfaces.IncidentEventRepository;
import com.logguardian.persistance.interfaces.IncidentRepository;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentPersistence {

    private final IncidentRepository incidentRepository;
    private final IncidentEventRepository incidentEventRepository;

    public IncidentDocument findIncident(String fingerprint, String sourceId) {
        return incidentRepository.findByFingerprintAndSourceId(fingerprint, sourceId);
    }

    public Optional<IncidentDocument> findIncidentById(String incidentId) {
        return incidentRepository.findById(incidentId);
    }

    @CachePut(value = "incident", key = "#incidentDocument.fingerprint + ':' + #incidentDocument.sourceId")
    public IncidentDocument saveIncident(IncidentDocument incidentDocument) {
        return incidentRepository.save(incidentDocument);
    }

    public IncidentEventDocument saveIncidentEvent(IncidentEventDocument incidentEventDocument) {
        return incidentEventRepository.save(incidentEventDocument);
    }

    public List<IncidentEventDocument> findIncidentEvents(String incidentId) {
        return incidentEventRepository.findAllByIncidentIdOrderByCreatedAtDesc(incidentId);
    }

    public List<IncidentEventDocument> findIncidentEventsByType(String incidentId, IncidentEventType type) {
        return incidentEventRepository.findAllByIncidentIdAndTypeOrderByCreatedAtDesc(incidentId, type);
    }

    public IncidentEventDocument findLatestIncidentEvent(String incidentId) {
        return incidentEventRepository.findTopByIncidentIdOrderByCreatedAtDesc(incidentId);
    }
}
