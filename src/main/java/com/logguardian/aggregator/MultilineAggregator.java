package com.logguardian.aggregator;

import com.logguardian.model.LogEntry;
import com.logguardian.model.LogLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class MultilineAggregator {

    private final int maxLines;
    private final long idleFlushMs;

    public MultilineAggregator(
            @Value("${logguardian.ingest.multiline.max-lines:500}") int maxLines,
            @Value("${logguardian.ingest.multiline.idle-flush-ms:800}") long idleFlushMs
    ) {
        this.maxLines = maxLines;
        this.idleFlushMs = idleFlushMs;
    }

    public Flux<LogEntry> transform(Flux<LogLine> lineFlux) {
        return Flux.create(sink -> {
            List<LogLine> currentEntry = new ArrayList<>();
            AtomicReference<Disposable> idleFlushTask = new AtomicReference<>();
            Object monitor = new Object();

            Disposable upstreamSubscription = lineFlux.subscribe(
                    line -> {
                        synchronized (monitor) {
                            if (!currentEntry.isEmpty() && isNewEntryStart(line.line())) {
                                sink.next(toEntry(currentEntry));
                                currentEntry.clear();
                            }

                            currentEntry.add(line);
                            if (currentEntry.size() >= maxLines) {
                                sink.next(toEntry(currentEntry));
                                currentEntry.clear();
                            }

                            rescheduleIdleFlush(sink, currentEntry, idleFlushTask, monitor);
                        }
                    },
                    error -> {
                        cancelIdleFlush(idleFlushTask);
                        sink.error(error);
                    },
                    () -> {
                        cancelIdleFlush(idleFlushTask);
                        synchronized (monitor) {
                            if (!currentEntry.isEmpty()) {
                                sink.next(toEntry(currentEntry));
                            }
                        }
                        sink.complete();
                    }
            );

            sink.onDispose(() -> {
                cancelIdleFlush(idleFlushTask);
                upstreamSubscription.dispose();
            });
        });
    }

    private boolean isNewEntryStart(String msg) {
        return switch (msg) {
            case null -> true;
            case "" -> true;
            default -> !(msg.isBlank()
                    || msg.startsWith(" ")
                    || msg.startsWith("\t")
                    || msg.startsWith("at ")
                    || msg.startsWith("Caused by:")
                    || msg.startsWith("..."));
        };
    }

    private LogEntry toEntry(List<LogLine> lines) {
        LogLine first = lines.getFirst();
        String joined = lines.stream()
                .map(LogLine::line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");

        return new LogEntry(first.containerId(), first.receivedAt(), joined);
    }

    private void rescheduleIdleFlush(reactor.core.publisher.FluxSink<LogEntry> sink,
                                     List<LogLine> currentEntry,
                                     AtomicReference<Disposable> idleFlushTask,
                                     Object monitor) {
        cancelIdleFlush(idleFlushTask);
        idleFlushTask.set(Schedulers.parallel().schedule(() -> {
            synchronized (monitor) {
                if (currentEntry.isEmpty()) {
                    return;
                }
                sink.next(toEntry(currentEntry));
                currentEntry.clear();
            }
        }, idleFlushMs, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    private void cancelIdleFlush(AtomicReference<Disposable> idleFlushTask) {
        Disposable scheduledTask = idleFlushTask.getAndSet(null);
        if (scheduledTask != null) {
            scheduledTask.dispose();
        }
    }

}
