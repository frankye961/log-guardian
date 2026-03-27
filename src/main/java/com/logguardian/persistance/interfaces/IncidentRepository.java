package com.logguardian.persistance.interfaces;

import com.logguardian.persistance.pojo.IncidentDocument;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentRepository extends MongoRepository<IncidentDocument, String> {
    @Cacheable(value = "incident", key = "#fingerprint + ':' + #sourceId")
    IncidentDocument findByFingerprintAndSourceId(String fingerprint, String sourceId);

    List<IncidentDocument> findTop8ByOrderByLastSeenAtDesc();
}
