package com.logguardian.persistance.pojo;

import com.logguardian.ai.model.IncidentSeverity;
import com.logguardian.parser.model.LogLevel;
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
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "incidents")
@CompoundIndexes({
        @CompoundIndex(name = "incident_lookup_idx", def = "{'fingerprint': 1, 'sourceId': 1, 'status': 1}"),
        @CompoundIndex(name = "incident_source_status_idx", def = "{'sourceName': 1, 'status': 1, 'lastSeenAt': -1}")
})
public class IncidentDocument {

    @Id
    private String id;

    @Indexed
    private String fingerprint;

    @Indexed
    private String sourceId;

    private String sourceName;

    private LogLevel logLevel;

    private IncidentStatus status;

    private IncidentSeverity severity;

    private Instant firstSeenAt;

    @Indexed
    private Instant lastSeenAt;

    private Instant acknowledgedAt;

    private Instant resolvedAt;

    private Integer totalOccurrences;

    private Integer lastWindowCount;

    private String sampleMessage;

    @Builder.Default
    private List<String> sampleMessages = new ArrayList<>();

    private String aiTitle;

    private String aiSummary;

    private String probableCause;

    private String suggestedActions;

    private Integer regressionCount;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    public void addSampleMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (sampleMessages == null) {
            sampleMessages = new ArrayList<>();
        }
        sampleMessages.add(message);
        sampleMessage = message;
    }
}
