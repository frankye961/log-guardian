package com.logguardian.gui;

import com.logguardian.mapper.IncidentMapper;
import com.logguardian.persistance.IncidentPersistence;
import com.logguardian.persistance.interfaces.IncidentRepository;
import com.logguardian.persistance.pojo.IncidentDocument;
import com.logguardian.persistance.pojo.IncidentEventType;
import com.logguardian.persistance.pojo.IncidentStatus;
import com.logguardian.service.runtime.LogSource;
import com.logguardian.service.runtime.LogStreamingService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DashboardService {

    private static final String SOURCE_KEY_SEPARATOR = "::";
    private static final Duration SOURCE_CACHE_TTL = Duration.ofSeconds(3);
    private static final Duration INCIDENT_CACHE_TTL = Duration.ofSeconds(10);
    private static final List<IncidentEventType> MANAGEABLE_EVENT_TYPES = List.of(
            IncidentEventType.ACKNOWLEDGED,
            IncidentEventType.SUPPRESSED,
            IncidentEventType.RESOLVED,
            IncidentEventType.REGRESSED,
            IncidentEventType.CLOSED
    );

    private final Map<String, LogStreamingService> servicesByRuntime;
    private final IncidentRepository incidentRepository;
    private final IncidentPersistence incidentPersistence;
    private final IncidentMapper incidentMapper;
    private final AtomicInteger jobSequence = new AtomicInteger();
    private final ConcurrentMap<Integer, TailJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> sourceOwners = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedSources> sourceCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicBoolean> sourceRefreshInProgress = new ConcurrentHashMap<>();
    private final AtomicBoolean incidentRefreshInProgress = new AtomicBoolean(false);
    private volatile CachedIncidents incidentCache = new CachedIncidents(List.of(), Instant.EPOCH);

    public DashboardService(
            List<LogStreamingService> services,
            IncidentRepository incidentRepository,
            IncidentPersistence incidentPersistence,
            IncidentMapper incidentMapper
    ) {
        this.servicesByRuntime = indexServices(services);
        this.incidentRepository = incidentRepository;
        this.incidentPersistence = incidentPersistence;
        this.incidentMapper = incidentMapper;
    }

    public DashboardSnapshot getSnapshot() {
        List<RuntimeSummary> runtimes = servicesByRuntime.keySet().stream()
                .sorted()
                .parallel()
                .map(runtimeKey -> {
                    List<LogSource> sources = safeListSources(runtimeKey);
                    List<RuntimeSourceView> sourceViews = sources.stream()
                            .map(source -> toRuntimeSourceView(runtimeKey, source))
                            .toList();
                    long activeCount = sourceViews.stream()
                            .filter(RuntimeSourceView::active)
                            .count();
                    return new RuntimeSummary(runtimeKey, sourceViews.size(), (int) activeCount, sourceViews);
                })
                .sorted(Comparator.comparing(RuntimeSummary::runtimeKey))
                .toList();

        List<TailJobView> activeJobs = listJobs();
        List<IncidentCard> incidents = recentIncidents(8);
        long openIncidents = incidents.stream()
                .filter(incident -> "OPEN".equalsIgnoreCase(incident.status()))
                .count();

        return new DashboardSnapshot(
                runtimes,
                activeJobs,
                incidents,
                new OverviewMetrics(runtimes.size(), activeJobs.size(), openIncidents, incidents.size())
        );
    }

    public List<RuntimeSourceView> listSources(String runtimeKey) {
        String runtime = normalizeRuntime(runtimeKey);
        return safeListSources(runtime).stream()
                .map(source -> toRuntimeSourceView(runtime, source))
                .toList();
    }

    public List<TailJobView> listJobs() {
        return jobs.values().stream()
                .filter(job -> !job.disposed())
                .sorted(Comparator.comparing(TailJob::startedAt).reversed())
                .map(this::toView)
                .toList();
    }

    public TailJobView startTailAll(String runtimeKey) {
        String runtime = normalizeRuntime(runtimeKey);
        List<LogSource> sources = refreshSourcesNow(runtime);
        if (sources.isEmpty()) {
            throw new IllegalStateException("No running sources found for runtime " + runtime);
        }

        List<LogSource> availableSources = sources.stream()
                .filter(source -> !sourceOwners.containsKey(sourceKey(runtime, source.id())))
                .toList();
        if (availableSources.isEmpty()) {
            throw new IllegalStateException("All running sources are already being tailed for runtime " + runtime);
        }

        List<String> sourceIds = availableSources.stream()
                .map(LogSource::id)
                .toList();
        List<Disposable> subscriptions = sourceIds.stream()
                .map(sourceId -> runtimeService(runtime).startStream(sourceId))
                .toList();

        TailJob job = registerJob(runtime, "tail-all", sourceIds, subscriptions);
        return toView(job);
    }

    public TailJobView startTailOne(String runtimeKey, String sourceId) {
        String runtime = normalizeRuntime(runtimeKey);
        String sourceKey = sourceKey(runtime, sourceId);
        Integer existingJobId = sourceOwners.get(sourceKey);
        if (existingJobId != null) {
            TailJob existingJob = jobs.get(existingJobId);
            if (existingJob != null && !existingJob.disposed()) {
                return toView(existingJob);
            }
        }

        Disposable subscription = runtimeService(runtime).startStream(sourceId);
        TailJob job = registerJob(runtime, "tail-one", List.of(sourceId), List.of(subscription));
        return toView(job);
    }

    public TailJobView stopJob(int jobId) {
        TailJob job = jobs.remove(jobId);
        if (job == null) {
            throw new IllegalStateException("Tail job not found: " + jobId);
        }

        job.subscriptions().forEach(Disposable::dispose);
        job.sourceIds().forEach(sourceId -> sourceOwners.remove(sourceKey(job.runtimeKey(), sourceId), job.id()));

        return new TailJobView(job.id(), job.runtimeKey(), job.command(), job.sourceIds(), job.startedAt(), "stopped");
    }

    public List<IncidentCard> recentIncidents(int limit) {
        Instant now = Instant.now();
        CachedIncidents cached = incidentCache;
        if (cached.isFresh(now)) {
            return cached.limit(limit);
        }

        refreshIncidentsAsync();
        return cached.limit(limit);
    }

    public IncidentCard updateIncidentEventType(String incidentId, IncidentEventType type, String note) {
        IncidentDocument incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IllegalStateException("Incident not found: " + incidentId));

        applyIncidentState(incident, type, Instant.now());
        //TODO In case the incident is CLOSED state, has to be deleted from db and cache
        IncidentDocument savedIncident = incidentPersistence.saveIncident(incident);
        incidentPersistence.saveIncidentEvent(
                incidentMapper.toIncidentStateEventDocument(savedIncident, type, "gui", StringUtils.trimToNull(note))
        );

        incidentCache = new CachedIncidents(rebuildIncidentCards(), Instant.now());
        return toIncidentCard(savedIncident);
    }

    private TailJob registerJob(String runtime, String command, List<String> sourceIds, List<Disposable> subscriptions) {
        int jobId = jobSequence.incrementAndGet();
        TailJob job = new TailJob(jobId, runtime, command, List.copyOf(sourceIds), List.copyOf(subscriptions), Instant.now());
        jobs.put(jobId, job);
        sourceIds.forEach(sourceId -> sourceOwners.put(sourceKey(runtime, sourceId), jobId));
        return job;
    }

    private TailJobView toView(TailJob job) {
        return new TailJobView(
                job.id(),
                job.runtimeKey(),
                job.command(),
                job.sourceIds(),
                job.startedAt(),
                job.disposed() ? "stopped" : "running"
        );
    }

    private IncidentCard toIncidentCard(IncidentDocument document) {
        return new IncidentCard(
                document.getId(),
                buildLogFirstTitle(document),
                StringUtils.defaultIfBlank(document.getSourceName(), document.getSourceId()),
                document.getSourceId(),
                document.getSeverity() == null ? "UNKNOWN" : document.getSeverity().name(),
                document.getStatus() == null ? IncidentStatus.OPEN.name() : document.getStatus().name(),
                document.getLastSeenAt(),
                buildLogFirstSummary(document),
                MANAGEABLE_EVENT_TYPES.stream().map(Enum::name).toList()
        );
    }

    private String buildLogFirstTitle(IncidentDocument document) {
        String firstSample = allSampleMessages(document).stream()
                .findFirst()
                .orElse(null);
        if (StringUtils.isNotBlank(firstSample)) {
            return abbreviate(firstSample, 120);
        }
        return StringUtils.defaultIfBlank(document.getSourceName(), "Container incident");
    }

    private String buildLogFirstSummary(IncidentDocument document) {
        List<String> samples = allSampleMessages(document);
        if (!samples.isEmpty()) {
            return String.join("\n", samples);
        }
        return "No sample logs available";
    }

    private List<String> allSampleMessages(IncidentDocument document) {
        if (document.getSampleMessages() != null && !document.getSampleMessages().isEmpty()) {
            return document.getSampleMessages().stream()
                    .filter(StringUtils::isNotBlank)
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                            List::copyOf
                    ));
        }
        if (StringUtils.isNotBlank(document.getSampleMessage())) {
            return List.of(document.getSampleMessage());
        }
        return List.of();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private void refreshIncidentsAsync() {
        if (!incidentRefreshInProgress.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                incidentCache = new CachedIncidents(rebuildIncidentCards(), Instant.now());
            } finally {
                incidentRefreshInProgress.set(false);
            }
        });
    }

    private List<IncidentCard> rebuildIncidentCards() {
        try {
            return incidentRepository.findTop8ByOrderByLastSeenAtDesc().stream()
                    .map(this::toIncidentCard)
                    .toList();
        } catch (Exception exception) {
            return incidentCache.cards();
        }
    }

    private void applyIncidentState(IncidentDocument incident, IncidentEventType type, Instant now) {
        switch (type) {
            case ACKNOWLEDGED -> {
                incident.setStatus(IncidentStatus.ACKNOWLEDGED);
                if (incident.getAcknowledgedAt() == null) {
                    incident.setAcknowledgedAt(now);
                }
            }
            case SUPPRESSED -> incident.setStatus(IncidentStatus.SUPPRESSED);
            case RESOLVED -> {
                incident.setStatus(IncidentStatus.RESOLVED);
                incident.setResolvedAt(now);
            }
            case REGRESSED -> {
                incident.setStatus(IncidentStatus.REGRESSED);
                incident.setResolvedAt(null);
            }
            case CLOSED -> {
                incident.setStatus(IncidentStatus.CLOSED);
                if (incident.getResolvedAt() == null) {
                    incident.setResolvedAt(now);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported GUI incident event type: " + type);
        }
    }

    private RuntimeSourceView toRuntimeSourceView(String runtime, LogSource source) {
        return new RuntimeSourceView(
                source.id(),
                source.name(),
                source.status(),
                sourceOwners.containsKey(sourceKey(runtime, source.id()))
        );
    }

    private LogStreamingService runtimeService(String runtime) {
        LogStreamingService service = servicesByRuntime.get(runtime);
        if (service == null) {
            throw new IllegalArgumentException("Unknown runtime: " + runtime);
        }
        return service;
    }

    private List<LogSource> safeListSources(String runtime) {
        Instant now = Instant.now();
        CachedSources cachedSources = sourceCache.get(runtime);
        if (cachedSources != null && cachedSources.isFresh(now)) {
            return cachedSources.sources();
        }

        refreshSourcesAsync(runtime);
        return cachedSources == null ? List.of() : cachedSources.sources();
    }

    private void refreshSourcesAsync(String runtime) {
        AtomicBoolean guard = sourceRefreshInProgress.computeIfAbsent(runtime, _runtime -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                refreshSourcesNow(runtime);
            } finally {
                guard.set(false);
            }
        });
    }

    private List<LogSource> refreshSourcesNow(String runtime) {
        Instant now = Instant.now();
        CachedSources cachedSources = sourceCache.get(runtime);
        try {
            List<LogSource> sources = runtimeService(runtime).listRunningSources();
            sourceCache.put(runtime, new CachedSources(List.copyOf(sources), now));
            return sources;
        } catch (Exception exception) {
            return cachedSources == null ? List.of() : cachedSources.sources();
        }
    }

    private Map<String, LogStreamingService> indexServices(List<LogStreamingService> services) {
        Map<String, LogStreamingService> indexed = new LinkedHashMap<>();
        for (LogStreamingService service : services) {
            Set<String> keys = new LinkedHashSet<>(service.runtimeKeys());
            for (String key : keys) {
                if (StringUtils.isBlank(key)) {
                    continue;
                }
                indexed.put(key.trim().toLowerCase(), service);
            }
        }
        return indexed;
    }

    private String normalizeRuntime(String runtimeKey) {
        if (StringUtils.isBlank(runtimeKey)) {
            throw new IllegalArgumentException("Runtime key must not be blank");
        }
        return runtimeKey.trim().toLowerCase();
    }

    private String sourceKey(String runtime, String sourceId) {
        return runtime + SOURCE_KEY_SEPARATOR + sourceId;
    }

    public record DashboardSnapshot(
            List<RuntimeSummary> runtimes,
            List<TailJobView> jobs,
            List<IncidentCard> incidents,
            OverviewMetrics metrics
    ) {
    }

    public record OverviewMetrics(
            int connectedRuntimes,
            int activeJobs,
            long openIncidents,
            int visibleIncidents
    ) {
    }

    public record RuntimeSummary(
            String runtimeKey,
            int runningSources,
            int activeSources,
            List<RuntimeSourceView> sources
    ) {
    }

    public record RuntimeSourceView(String id, String name, String status, boolean active) {
    }

    public record TailJobView(
            int id,
            String runtimeKey,
            String command,
            List<String> sourceIds,
            Instant startedAt,
            String status
    ) {
    }

    public record IncidentCard(
            String id,
            String title,
            String sourceName,
            String sourceId,
            String severity,
            String status,
            Instant lastSeenAt,
            String summary,
            List<String> availableEventTypes
    ) {
    }

    private record TailJob(
            int id,
            String runtimeKey,
            String command,
            List<String> sourceIds,
            List<Disposable> subscriptions,
            Instant startedAt
    ) {
        private boolean disposed() {
            return subscriptions.stream().allMatch(Disposable::isDisposed);
        }
    }

    private record CachedSources(List<LogSource> sources, Instant capturedAt) {
        private boolean isFresh(Instant now) {
            return capturedAt.plus(SOURCE_CACHE_TTL).isAfter(now);
        }
    }

    private record CachedIncidents(List<IncidentCard> cards, Instant capturedAt) {
        private boolean isFresh(Instant now) {
            return capturedAt.plus(INCIDENT_CACHE_TTL).isAfter(now);
        }

        private List<IncidentCard> limit(int limit) {
            return cards.stream()
                    .limit(Math.max(0, limit))
                    .toList();
        }
    }
}
