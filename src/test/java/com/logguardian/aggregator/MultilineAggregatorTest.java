package com.logguardian.aggregator;

import com.logguardian.model.LogEntry;
import com.logguardian.model.LogLine;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MultilineAggregatorTest {

    private final MultilineAggregator aggregator = new MultilineAggregator(500, 10_000);

    @Test
    void groupsIndentedStackTraceLinesIntoSingleEntry() {
        Instant now = Instant.parse("2026-03-21T12:00:00Z");

        Flux<LogEntry> result = aggregator.transform(Flux.just(
                LogLine.builder().containerId("c-1").receivedAt(now).line("2026-03-21 12:00:00 ERROR top level").build(),
                LogLine.builder().containerId("c-1").receivedAt(now).line("    at app.Service.method(Service.java:42)").build(),
                LogLine.builder().containerId("c-1").receivedAt(now).line("Caused by: java.lang.IllegalStateException").build(),
                LogLine.builder().containerId("c-1").receivedAt(now).line("2026-03-21 12:00:01 INFO recovered").build()
        ));

        StepVerifier.create(result)
                .expectNextMatches(entry -> entry.message().equals("2026-03-21 12:00:00 ERROR top level\n" +
                        "    at app.Service.method(Service.java:42)\n" +
                        "Caused by: java.lang.IllegalStateException"))
                .expectNextMatches(entry -> entry.message().equals("2026-03-21 12:00:01 INFO recovered"))
                .verifyComplete();
    }

    @Test
    void cancelsUpstreamSubscriptionWhenDownstreamCancels() {
        MultilineAggregator fastAggregator = new MultilineAggregator(500, 10_000);
        TestPublisher<LogLine> source = TestPublisher.create();

        StepVerifier.create(fastAggregator.transform(source.flux()), 1)
                .then(() -> source.next(LogLine.builder()
                        .containerId("c-1")
                        .receivedAt(Instant.parse("2026-03-21T12:00:00Z"))
                        .line("2026-03-21 12:00:00 ERROR top level")
                        .build()))
                .thenCancel()
                .verify();

        assertThat(source.wasCancelled()).isTrue();
    }

}
