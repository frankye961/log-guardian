package com.logguardian.fingerprint.anomaly;

import com.logguardian.fingerprint.window.CountedLogEvent;
import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.model.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest {

    @Test
    void honorsConfiguredMinimumCountThreshold() {
        AnomalyDetector detector = new AnomalyDetector(3);
        LogEvent event = new LogEvent(
                "DOCKER",
                "container-1",
                null,
                Instant.parse("2026-03-21T12:00:00Z"),
                Instant.parse("2026-03-21T12:00:00Z"),
                LogLevel.ERROR,
                "failed request",
                "fp-1",
                Map.of()
        );

        assertThat(detector.detectAnomaly(new CountedLogEvent(event, 3))).isEmpty();
        assertThat(detector.detectAnomaly(new CountedLogEvent(event, 4))).isPresent();
    }
}
