package com.logguardian.ai.model;

import lombok.Builder;

import static com.logguardian.ai.model.IncidentSeverity.UNKNOWN;

@Builder
public record IncidentSummary(
        String title,
        String summary,
        String probableCause,
        String suggestedActions,
        IncidentSeverity severity
) {

    public static IncidentSummary empty() {
        return new IncidentSummary(
                "Unknown incident",
                "No summary available",
                "Unknown",
                "No suggested actions available",
                UNKNOWN
        );
    }
}
