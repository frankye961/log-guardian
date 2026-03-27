package com.logguardian.mapper;

import com.logguardian.ai.model.IncidentSummary;
import com.logguardian.fingerprint.anomaly.AnomalyEvent;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import com.logguardian.persistance.pojo.IncidentStatus;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        builder = @Builder(disableBuilder = false),
        imports = {IncidentStatus.class, ArrayList.class}
)
public interface IncidentMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "logLevel", source = "anomaly.level")
    @Mapping(target = "status", expression = "java(IncidentStatus.OPEN)")
    @Mapping(target = "severity", source = "summary.severity")
    @Mapping(target = "firstSeenAt", source = "anomaly.detectedAt")
    @Mapping(target = "lastSeenAt", source = "anomaly.detectedAt")
    @Mapping(target = "acknowledgedAt", ignore = true)
    @Mapping(target = "resolvedAt", ignore = true)
    @Mapping(target = "totalOccurrences", source = "anomaly.count")
    @Mapping(target = "lastWindowCount", source = "anomaly.count")
    @Mapping(target = "sampleMessage", source = "anomaly.sampleMessage")
    @Mapping(target = "sampleMessages", expression = "java(toSampleMessages(anomaly.sampleMessage()))")
    @Mapping(target = "aiTitle", source = "summary.title")
    @Mapping(target = "aiSummary", source = "summary.summary")
    @Mapping(target = "probableCause", source = "summary.probableCause")
    @Mapping(target = "suggestedActions", source = "summary.suggestedActions")
    @Mapping(target = "regressionCount", expression = "java(0)")
    @Mapping(target = "tags", expression = "java(new ArrayList<>())")
    IncidentDocument toIncidentDocument(AnomalyEvent anomaly, IncidentSummary summary);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "incidentId", source = "incidentId")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "fingerprint", source = "anomaly.fingerprint")
    @Mapping(target = "sourceId", source = "anomaly.sourceId")
    @Mapping(target = "sourceName", source = "anomaly.sourceName")
    @Mapping(target = "anomalyCount", source = "anomaly.count")
    @Mapping(target = "severity", source = "summary.severity")
    @Mapping(target = "title", source = "summary.title")
    @Mapping(target = "summary", source = "summary.summary")
    @Mapping(target = "probableCause", source = "summary.probableCause")
    @Mapping(target = "suggestedActions", source = "summary.suggestedActions")
    @Mapping(target = "sampleMessage", source = "anomaly.sampleMessage")
    @Mapping(target = "actor", source = "actor")
    @Mapping(target = "note", source = "note")
    IncidentEventDocument toIncidentEventDocument(
            String incidentId,
            AnomalyEvent anomaly,
            IncidentSummary summary,
            IncidentEventType type,
            Instant createdAt,
            String actor,
            String note
    );

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "incidentId", source = "incident.id")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "fingerprint", source = "incident.fingerprint")
    @Mapping(target = "sourceId", source = "incident.sourceId")
    @Mapping(target = "sourceName", source = "incident.sourceName")
    @Mapping(target = "anomalyCount", source = "incident.lastWindowCount")
    @Mapping(target = "severity", source = "incident.severity")
    @Mapping(target = "title", source = "incident.aiTitle")
    @Mapping(target = "summary", source = "incident.aiSummary")
    @Mapping(target = "probableCause", source = "incident.probableCause")
    @Mapping(target = "suggestedActions", source = "incident.suggestedActions")
    @Mapping(target = "sampleMessage", source = "incident.sampleMessage")
    @Mapping(target = "actor", source = "actor")
    @Mapping(target = "note", source = "note")
    IncidentEventDocument toIncidentEventDocumentFromState(
            IncidentDocument incident,
            IncidentEventType type,
            Instant createdAt,
            String actor,
            String note
    );

    default IncidentEventDocument toCreatedIncidentEventDocument(
            String incidentId,
            AnomalyEvent anomaly,
            IncidentSummary summary
    ) {
        return toIncidentEventDocument(
                incidentId,
                anomaly,
                summary,
                IncidentEventType.CREATED,
                anomaly.detectedAt(),
                null,
                null
        );
    }

    default IncidentEventDocument toUpdatedIncidentEventDocument(
            String incidentId,
            AnomalyEvent anomaly,
            IncidentSummary summary,
            IncidentEventType type,
            String actor,
            String note
    ) {
        return toIncidentEventDocument(
                incidentId,
                anomaly,
                summary,
                type,
                anomaly.detectedAt(),
                actor,
                note
        );
    }

    default IncidentEventDocument toIncidentStateEventDocument(
            IncidentDocument incident,
            IncidentEventType type,
            String actor,
            String note
    ) {
        return toIncidentEventDocumentFromState(
                incident,
                type,
                Instant.now(),
                actor,
                note
        );
    }

    default void updateIncidentDocument(
            @MappingTarget IncidentDocument existing,
            AnomalyEvent anomaly,
            IncidentSummary summary
    ) {
        existing.setFingerprint(anomaly.fingerprint());
        existing.setSourceId(anomaly.sourceId());
        existing.setSourceName(anomaly.sourceName());
        existing.setLogLevel(anomaly.level());
        existing.setLastSeenAt(anomaly.detectedAt());
        existing.setLastWindowCount(anomaly.count());
        existing.setStatus(resolveStatus(existing));

        Integer currentTotal = existing.getTotalOccurrences();
        existing.setTotalOccurrences((currentTotal == null ? 0 : currentTotal) + anomaly.count());

        if (summary != null) {
            existing.setSeverity(summary.severity());
            existing.setAiTitle(summary.title());
            existing.setAiSummary(summary.summary());
            existing.setProbableCause(summary.probableCause());
            existing.setSuggestedActions(summary.suggestedActions());
        }

        addSampleMessage(existing, anomaly.sampleMessage());
    }

    default ArrayList<String> toSampleMessages(String sampleMessage) {
        ArrayList<String> samples = new ArrayList<>();
        if (sampleMessage != null && !sampleMessage.isBlank()) {
            samples.add(sampleMessage);
        }
        return samples;
    }

    default void addSampleMessage(IncidentDocument incident, String sampleMessage) {
        if (sampleMessage == null || sampleMessage.isBlank()) {
            return;
        }
        if (incident.getSampleMessages() == null) {
            incident.setSampleMessages(new ArrayList<>());
        } else if (!(incident.getSampleMessages() instanceof ArrayList)) {
            incident.setSampleMessages(new ArrayList<>(incident.getSampleMessages()));
        }
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>(incident.getSampleMessages());
        deduplicated.add(sampleMessage);
        incident.setSampleMessages(new ArrayList<>(deduplicated));
        incident.setSampleMessage(sampleMessage);
    }

    default IncidentStatus resolveStatus(IncidentDocument existing) {
        if (existing.getStatus() == IncidentStatus.RESOLVED || existing.getStatus() == IncidentStatus.CLOSED) {
            Integer currentRegressionCount = existing.getRegressionCount();
            existing.setRegressionCount((currentRegressionCount == null ? 0 : currentRegressionCount) + 1);
            existing.setResolvedAt(null);
            return IncidentStatus.REGRESSED;
        }
        return existing.getStatus() == null ? IncidentStatus.OPEN : existing.getStatus();
    }
}
