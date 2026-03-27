package com.logguardian.gui;

import com.logguardian.persistance.pojo.IncidentEventType;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public DashboardService.DashboardSnapshot dashboard() {
        return dashboardService.getSnapshot();
    }

    @GetMapping(value = "/dashboard/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<DashboardService.DashboardSnapshot> dashboardStream() {
        return Flux.interval(Duration.ofSeconds(3))
                .startWith(0L)
                .map(_tick -> dashboardService.getSnapshot());
    }

    @GetMapping("/runtimes/{runtimeKey}/sources")
    public Object sources(@PathVariable String runtimeKey) {
        return dashboardService.listSources(runtimeKey);
    }

    @PostMapping("/runtimes/{runtimeKey}/tail-all")
    public DashboardService.TailJobView tailAll(@PathVariable String runtimeKey) {
        return dashboardService.startTailAll(runtimeKey);
    }

    @PostMapping("/runtimes/{runtimeKey}/tail-one")
    public DashboardService.TailJobView tailOne(@PathVariable String runtimeKey, @RequestParam String sourceId) {
        return dashboardService.startTailOne(runtimeKey, sourceId);
    }

    @GetMapping("/jobs")
    public Object jobs() {
        return dashboardService.listJobs();
    }

    @DeleteMapping("/jobs/{jobId}")
    public DashboardService.TailJobView stopJob(@PathVariable int jobId) {
        return dashboardService.stopJob(jobId);
    }

    @GetMapping("/incidents")
    public Object incidents(@RequestParam(defaultValue = "12") int limit) {
        return dashboardService.recentIncidents(limit);
    }

    @PostMapping("/incidents/{incidentId}/event-type")
    public DashboardService.IncidentCard updateIncidentEventType(
            @PathVariable String incidentId,
            @RequestParam IncidentEventType type,
            @RequestParam(required = false) String note
    ) {
        return dashboardService.updateIncidentEventType(incidentId, type, note);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> handleRuntimeErrors(RuntimeException exception) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", exception.getMessage()));
    }
}
