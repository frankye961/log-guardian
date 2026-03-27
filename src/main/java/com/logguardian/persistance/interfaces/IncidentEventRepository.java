package com.logguardian.persistance.interfaces;

import com.logguardian.persistance.pojo.IncidentEventDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentEventRepository extends MongoRepository<IncidentEventDocument, String> {

    List<IncidentEventDocument> findAllByIncidentIdOrderByCreatedAtDesc(String incidentId);

    List<IncidentEventDocument> findAllByIncidentIdAndTypeOrderByCreatedAtDesc(String incidentId, IncidentEventType type);

    IncidentEventDocument findTopByIncidentIdOrderByCreatedAtDesc(String incidentId);
}
