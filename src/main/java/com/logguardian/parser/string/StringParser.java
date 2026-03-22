package com.logguardian.parser.string;

import com.logguardian.model.LogEntry;
import com.logguardian.parser.BaseParser;
import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.model.LogLevel;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StringParser implements BaseParser {

    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("\\b(?<ts>\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\b");

    private static final Pattern ERROR_PATTERN = Pattern.compile("\\bERROR\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WARN_PATTERN = Pattern.compile("\\bWARN(?:ING)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INFO_PATTERN = Pattern.compile("\\bINFO\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBUG_PATTERN = Pattern.compile("\\bDEBUG\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACE_PATTERN = Pattern.compile("\\bTRACE\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public LogEvent parse(LogEntry entry) {
        String raw = entry.message();
        String firstLine = extractFirstLine(raw);

        Instant eventTime = extractTimestamp(firstLine);
        LogLevel level = detectLevel(firstLine);

        return buildEvent(entry, eventTime, level, raw);
    }

    private LogEvent buildEvent(LogEntry entry,
                                Instant eventTime,
                                LogLevel level,
                                String message) {
        return new LogEvent(
                "DOCKER",
                entry.containerId(),
                null,
                entry.seen(),
                eventTime,
                level,
                message,
                null,
                Map.of()
        );
    }

    private String extractFirstLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        int idx = raw.indexOf('\n');
        if (idx < 0) {
            return raw.trim();
        }

        return raw.substring(0, idx).trim();
    }

    private Instant extractTimestamp(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        Matcher matcher = TIMESTAMP_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }

        String ts = matcher.group("ts");

        try {
            ts = ts.replace(",", ".");
            String normalized = ts.replace(" ", "T");

            if (hasExplicitOffset(normalized)) {
                return OffsetDateTime.parse(normalized).toInstant();
            }

            return LocalDateTime.parse(normalized)
                    .atOffset(ZoneOffset.UTC)
                    .toInstant();
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(ts).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private boolean hasExplicitOffset(String timestamp) {
        return timestamp.endsWith("Z")
                || timestamp.matches(".*[+-]\\d{2}:?\\d{2}$");
    }

    private LogLevel detectLevel(String line) {
        if (line == null || line.isBlank()) {
            return LogLevel.UNKNOWN;
        }

        String upper = line.toUpperCase(Locale.ROOT);

        if (ERROR_PATTERN.matcher(upper).find()) {
            return LogLevel.ERROR;
        }
        if (WARN_PATTERN.matcher(upper).find()) {
            return LogLevel.WARN;
        }
        if (INFO_PATTERN.matcher(upper).find()) {
            return LogLevel.INFO;
        }
        if (DEBUG_PATTERN.matcher(upper).find()) {
            return LogLevel.DEBUG;
        }
        if (TRACE_PATTERN.matcher(upper).find()) {
            return LogLevel.TRACE;
        }

        // heuristic for stacktraces / exceptions
        if (upper.contains("EXCEPTION") || upper.contains("ERROR")) {
            return LogLevel.ERROR;
        }

        return LogLevel.UNKNOWN;
    }
}
