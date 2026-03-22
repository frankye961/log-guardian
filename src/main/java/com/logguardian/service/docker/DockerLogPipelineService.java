package com.logguardian.service.docker;

import com.logguardian.aggregator.MultilineAggregator;
import com.logguardian.ai.AiIncidentSummarizer;
import com.logguardian.ai.model.IncidentSummary;
import com.logguardian.ai.model.IncidentSummaryRequest;
import com.logguardian.fingerprint.anomaly.AnomalyEvent;
import com.logguardian.fingerprint.anomaly.AnomalyDetector;
import com.logguardian.fingerprint.generator.FingerPrintGenerator;
import com.logguardian.fingerprint.window.CountedLogEvent;
import com.logguardian.fingerprint.window.FingerPrintWindowCounter;
import com.logguardian.mapper.AiSummerizerMapper;
import com.logguardian.model.LogEntry;
import com.logguardian.model.LogLine;
import com.logguardian.parser.json.JsonParser;
import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.string.StringParser;
import com.logguardian.service.email.EmailSenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static com.logguardian.util.Utils.checkIfJson;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerLogPipelineService {

    private final MultilineAggregator aggregator;
    private final StringParser stringParser;
    private final JsonParser jsonParser;
    private final FingerPrintGenerator fingerPrintGenerator;
    private final FingerPrintWindowCounter counter;
    private final AnomalyDetector detector;
    private final AiIncidentSummarizer summarizer;
    private final EmailSenderService emailSender;

    @Value("${logguardian.ai.enabled:true}")
    private boolean aiEnabled;

    public Flux<IncidentSummary> process(String containerId, Flux<LogLine> lines) {
        return lines
                .transform(aggregator::transform)
                .map(this::parseEntry)
                .map(fingerPrintGenerator::generateFingerprint)
                .map(this::countFingerprint)
                .flatMap(counted -> Mono.justOrEmpty(detector.detectAnomaly(counted)))
                .flatMap(this::buildIncidentNotification)
                .flatMap(notification -> Mono.fromCallable(() -> {
                            System.out.println("Sending incident notification");
                            emailSender.sendIncidentEmail(notification.anomaly(), notification.summary());
                            return notification.summary();
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnSubscribe(_ -> log.info("Stream started for container {}", containerId));
    }

    private CountedLogEvent countFingerprint(LogEvent event) {
        return new CountedLogEvent(event, counter.countFingerprint(event));
    }

    private LogEvent parseEntry(LogEntry entry) {
        String message = entry.message();
        if (checkIfJson(message)) {
            try {
                return jsonParser.parse(entry);
            } catch (RuntimeException exception) {
                log.warn("JSON parsing failed for container {}, falling back to string parser", entry.containerId(), exception);
            }
        }
        return stringParser.parse(entry);
    }

    private Mono<IncidentNotification> buildIncidentNotification(AnomalyEvent anomaly) {
        IncidentSummaryRequest request = AiSummerizerMapper.toIncidentSummaryRequest(anomaly);
        return summarizeIfEnabled(request)
                .defaultIfEmpty(IncidentSummary.empty())
                .map(summary -> new IncidentNotification(anomaly, summary));
    }

    private Mono<IncidentSummary> summarizeIfEnabled(IncidentSummaryRequest request) {
        if (!aiEnabled) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> summarizer.summarize(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private record IncidentNotification(AnomalyEvent anomaly, IncidentSummary summary) {
    }
}
