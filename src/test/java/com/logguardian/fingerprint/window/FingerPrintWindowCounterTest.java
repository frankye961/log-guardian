package com.logguardian.fingerprint.window;

import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.model.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FingerPrintWindowCounterTest {

    @Test
    void groupsEventsByConfiguredWindowSize() {
        FingerPrintWindowCounter counter = new FingerPrintWindowCounter(60);
        LogEvent event = new LogEvent(
                "DOCKER",
                "container-1",
                null,
                Instant.parse("2026-03-21T12:00:00Z"),
                Instant.parse("2026-03-21T12:00:29Z"),
                LogLevel.ERROR,
                "failure",
                "fp-1",
                Map.of()
        );

        LogEvent sameWindow = new LogEvent(
                "DOCKER",
                "container-1",
                null,
                Instant.parse("2026-03-21T12:00:01Z"),
                Instant.parse("2026-03-21T12:00:30Z"),
                LogLevel.ERROR,
                "failure",
                "fp-1",
                Map.of()
        );

        LogEvent nextWindow = new LogEvent(
                "DOCKER",
                "container-1",
                null,
                Instant.parse("2026-03-21T12:00:02Z"),
                Instant.parse("2026-03-21T12:01:01Z"),
                LogLevel.ERROR,
                "failure",
                "fp-1",
                Map.of()
        );

        assertThat(counter.countFingerprint(event)).isEqualTo(1);
        assertThat(counter.countFingerprint(sameWindow)).isEqualTo(2);
        assertThat(counter.countFingerprint(nextWindow)).isEqualTo(1);
    }
}
