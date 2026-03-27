package com.logguardian.persistance.pojo;

import com.logguardian.ai.model.IncidentSeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "incident_events")
@CompoundIndexes({
        @CompoundIndex(name = "incident_event_lookup_idx", def = "{'incidentId': 1, 'createdAt': -1}")
})
public class IncidentEventDocument {

    @Id
    private String id;

    @Indexed
    private String incidentId;

    private IncidentEventType type;

    private Instant createdAt;

    private String fingerprint;

    private String sourceId;

    private String sourceName;

    private Integer anomalyCount;

    private IncidentSeverity severity;

    private String title;

    private String summary;

    private String probableCause;

    private String suggestedActions;

    private String sampleMessage;

    private String actor;

    private String note;
}
