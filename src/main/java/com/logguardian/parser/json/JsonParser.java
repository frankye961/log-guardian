package com.logguardian.parser.json;

import com.logguardian.model.LogEntry;
import com.logguardian.parser.BaseParser;
import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.model.LogLevel;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

@Component
public class JsonParser implements BaseParser {

    private final ObjectMapper mapper;

    public JsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LogEvent parse(LogEntry entry) {
        try {
            Map<String, Object> json = mapper.readValue(entry.message(), Map.class);

            Instant eventTime = extractTimestamp(json);
            LogLevel level = extractLevel(json, entry.message());

            return new LogEvent(
                    "DOCKER",
                    entry.containerId(),
                    null,
                    entry.seen(),
                    eventTime,
                    level,
                    entry.message(),
                    null,
                    Map.of()
            );

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON log", e);
        }
    }

    private Instant extractTimestamp(Map<String, Object> json) {
        String rawTs = firstNonNull(json, "@timestamp", "timestamp", "ts", "time");

        if (rawTs == null || rawTs.isBlank()) {
            return null;
        }

        try {
            return OffsetDateTime.parse(rawTs).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(rawTs);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private LogLevel extractLevel(Map<String, Object> json, String rawMessage) {
        String rawLevel = firstNonNull(json, "level", "severity", "logLevel", "lvl");

        if (rawLevel != null) {
            LogLevel parsed = toLevel(rawLevel);
            if (parsed != LogLevel.UNKNOWN) {
                return parsed;
            }
        }

        return detectLevelFromText(rawMessage);
    }

    private LogLevel detectLevelFromText(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return LogLevel.UNKNOWN;
        }

        String upper = rawMessage.toUpperCase(Locale.ROOT);

        if (upper.contains("ERROR")) {
            return LogLevel.ERROR;
        }
        if (upper.contains("WARN") || upper.contains("WARNING")) {
            return LogLevel.WARN;
        }
        if (upper.contains("INFO")) {
            return LogLevel.INFO;
        }
        if (upper.contains("DEBUG")) {
            return LogLevel.DEBUG;
        }
        if (upper.contains("TRACE")) {
            return LogLevel.TRACE;
        }

        return LogLevel.UNKNOWN;
    }

    private LogLevel toLevel(String rawLevel) {
        if (rawLevel == null || rawLevel.isBlank()) {
            return LogLevel.UNKNOWN;
        }

        return switch (rawLevel.trim().toUpperCase(Locale.ROOT)) {
            case "ERROR" -> LogLevel.ERROR;
            case "WARN", "WARNING" -> LogLevel.WARN;
            case "INFO" -> LogLevel.INFO;
            case "DEBUG" -> LogLevel.DEBUG;
            case "TRACE" -> LogLevel.TRACE;
            default -> LogLevel.UNKNOWN;
        };
    }

    private String firstNonNull(Map<String, Object> json, String... keys) {
        for (String key : keys) {
            Object value = json.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}