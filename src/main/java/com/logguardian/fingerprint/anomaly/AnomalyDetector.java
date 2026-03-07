package com.logguardian.fingerprint.anomaly;

import com.logguardian.fingerprint.window.CountedLogEvent;
import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.model.LogLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
public class AnomalyDetector {

    private static final int ERROR_THRESHOLD = 20;

    public Optional<AnomalyEvent> detectAnomaly(CountedLogEvent countedEvent){
        LogEvent logEvent = countedEvent.event();

        if(logEvent.level() != LogLevel.ERROR){
            log.info("No log level detected... {}", countedEvent.event().level());
            return Optional.empty();
        }

        if (countedEvent.count() <= ERROR_THRESHOLD) {
            return Optional.empty();
        }
        log.info("Anomaly detected {}", countedEvent.event().level());
        return Optional.of(new AnomalyEvent(
                logEvent.fingerprint(),
                logEvent.level(),
                logEvent.sourceId(),
                logEvent.sourceName(),
                Instant.now(),
                countedEvent.count(),
                logEvent.message()
        ));
    }
}
