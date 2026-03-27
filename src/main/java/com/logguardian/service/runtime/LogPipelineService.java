package com.logguardian.service.runtime;

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
import com.logguardian.mapper.IncidentMapper;
import com.logguardian.model.LogEntry;
import com.logguardian.model.LogLine;
import com.logguardian.parser.json.JsonParser;
import com.logguardian.parser.model.LogEvent;
import com.logguardian.parser.string.StringParser;
import com.logguardian.persistance.IncidentPersistence;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import com.logguardian.persistance.pojo.IncidentStatus;
import com.logguardian.service.email.EmailSenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;

import static com.logguardian.util.Utils.checkIfJson;
import static com.logguardian.util.Utils.generateIncidentId;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogPipelineService {

    private final MultilineAggregator aggregator;
    private final StringParser stringParser;
    private final JsonParser jsonParser;
    private final FingerPrintGenerator fingerPrintGenerator;
    private final FingerPrintWindowCounter counter;
    private final AnomalyDetector detector;
    private final AiIncidentSummarizer summarizer;
    private final EmailSenderService emailSender;
    private final IncidentPersistence incidentPersistence;
    private final IncidentMapper mapper;

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
        IncidentDocument existing = incidentPersistence.findIncident(request.fingerprint(), request.sourceId());

        return summarizeIfEnabled(request)
                .defaultIfEmpty(existingSummary(existing))
                .map(summary -> persistIncident(existing, anomaly, summary))
                .map(document -> new IncidentNotification(anomaly, toIncidentSummary(document)));
    }

    private IncidentDocument persistIncident(IncidentDocument existing, AnomalyEvent anomaly, IncidentSummary summary) {

        if (existing == null) {
            IncidentDocument incident = mapper.toIncidentDocument(anomaly, summary);
            String incidentId = generateIncidentId(anomaly.fingerprint(),  anomaly.sourceId());
            incident.setStatus(IncidentStatus.OPEN);
            mapper.toIncidentEventDocument(incidentId,
                    anomaly,
                    summary,
                    IncidentEventType.CREATED,
                    Instant.now(),
                    "none",
                    summary.suggestedActions()
                    );
            return incidentPersistence.saveIncident(incident);
        }

        mapper.updateIncidentDocument(existing, anomaly, summary);
        return incidentPersistence.saveIncident(existing);
    }

    private IncidentSummary existingSummary(IncidentDocument document) {
        if (document == null) {
            return IncidentSummary.empty();
        }

        return toIncidentSummary(document);
    }

    private IncidentSummary toIncidentSummary(IncidentDocument document) {
        return IncidentSummary.builder()
                .title(document.getAiTitle())
                .summary(document.getAiSummary())
                .probableCause(document.getProbableCause())
                .suggestedActions(document.getSuggestedActions())
                .severity(document.getSeverity())
                .build();
    }

    //TODO implement method to persist incidentevent document
    private Mono<IncidentSummary> summarizeIfEnabled(IncidentSummaryRequest request) {
        if (!aiEnabled) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> summarizer.summarize(request))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(summary -> !isUnavailableAiSummary(summary));
    }

    private boolean isUnavailableAiSummary(IncidentSummary summary) {
        if (summary == null) {
            return true;
        }
        return "AI unavailable".equalsIgnoreCase(summary.title())
                && "Missing ChatModel bean".equalsIgnoreCase(summary.probableCause());
    }


    private record IncidentNotification(AnomalyEvent anomaly, IncidentSummary summary) {
    }
}
