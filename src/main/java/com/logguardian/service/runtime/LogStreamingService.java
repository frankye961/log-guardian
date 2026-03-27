package com.logguardian.service.runtime;

import reactor.core.Disposable;

import java.util.List;
import java.util.Set;

public interface LogStreamingService {

    Set<String> runtimeKeys();

    List<LogSource> listRunningSources();

    Disposable startStream(String sourceId);

    void stopAllStreams();
}
