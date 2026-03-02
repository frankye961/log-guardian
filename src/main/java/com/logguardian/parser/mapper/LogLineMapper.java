package com.logguardian.parser.mapper;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.logguardian.model.LogLine;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class LogLineMapper {

    public static LogLine map(Frame frame, String containerId) {

        String message = new String(frame.getPayload(), StandardCharsets.UTF_8);

        boolean isError = frame.getStreamType() == StreamType.STDERR;

        return new LogLine(
                "DOCKER",
                containerId,
                "containerName",
                Instant.now(),
                isError,
                message
        );
    }
}
