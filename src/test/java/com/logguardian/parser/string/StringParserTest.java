package com.logguardian.parser.string;

import com.logguardian.model.LogEntry;
import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.model.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StringParserTest {

    private final StringParser parser = new StringParser();

    @Test
    void extractsTimestampAndLevelFromFirstLine() {
        LogEntry entry = new LogEntry(
                "container-1",
                Instant.parse("2026-03-21T12:00:05Z"),
                """
                        2026-03-21 12:00:00,123 ERROR request failed
                            at app.Service.method(Service.java:42)
                        """
        );

        LogEvent event = parser.parse(entry);

        assertThat(event.level()).isEqualTo(LogLevel.ERROR);
        assertThat(event.eventTime()).isEqualTo(Instant.parse("2026-03-21T11:00:00.123Z"));
        assertThat(event.message()).contains("request failed");
    }

    @Test
    void leavesEventTimeNullWhenTimestampCannotBeParsed() {
        LogEntry entry = new LogEntry("container-1", Instant.now(), "WARN no timestamp present");

        LogEvent event = parser.parse(entry);

        assertThat(event.level()).isEqualTo(LogLevel.WARN);
        assertThat(event.eventTime()).isNull();
    }
}
