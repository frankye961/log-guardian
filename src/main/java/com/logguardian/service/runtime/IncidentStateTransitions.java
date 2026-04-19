package com.logguardian.service.runtime;

import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import com.logguardian.persistance.pojo.IncidentStatus;

import java.time.Instant;

public final class IncidentStateTransitions {

    private IncidentStateTransitions() {
    }

    public static void apply(IncidentDocument incident, IncidentEventType type, Instant now) {
        switch (type) {
            case ACKNOWLEDGED -> {
                incident.setStatus(IncidentStatus.ACKNOWLEDGED);
                if (incident.getAcknowledgedAt() == null) {
                    incident.setAcknowledgedAt(now);
                }
            }
            case SUPPRESSED -> incident.setStatus(IncidentStatus.SUPPRESSED);
            case RESOLVED -> {
                incident.setStatus(IncidentStatus.RESOLVED);
                incident.setResolvedAt(now);
            }
            case REGRESSED -> {
                incident.setStatus(IncidentStatus.REGRESSED);
                incident.setResolvedAt(null);
            }
            case CLOSED -> {
                incident.setStatus(IncidentStatus.CLOSED);
                if (incident.getResolvedAt() == null) {
                    incident.setResolvedAt(now);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported incident event type: " + type);
        }
    }
}
