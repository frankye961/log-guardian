package com.logguardian.model;

import lombok.Builder;

import java.time.Instant;

@Builder
public record LogLine(
        String sourceType,
        String containerId,
        String containerName,
        Instant receivedAt,
        boolean stderr,
        String line) {}
