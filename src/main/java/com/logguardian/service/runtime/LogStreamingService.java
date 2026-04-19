package com.logguardian.service.runtime;

import reactor.core.Disposable;

import java.util.List;
import java.util.Set;

public interface LogStreamingService {

    Set<String> runtimeKeys();

    List<LogSource> listRunningSources();

    Disposable startStream(String sourceId);

    void stopAllStreams();
    void acknowledgeIncident(String incidentId, String fingerprint, String sourceId);
    void closeIncident(String incidentId);
    void suppressIncident(String incidentId);
    void resolveIncident(String incidentId);
}
