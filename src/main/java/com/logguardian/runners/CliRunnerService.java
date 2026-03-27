package com.logguardian.runners;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import com.logguardian.service.runtime.LogSource;
import com.logguardian.service.runtime.LogStreamingService;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static com.logguardian.runners.Command.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "logguardian.mode", havingValue = "cli")
public class CliRunnerService implements CommandLineRunner {

    private final Map<String, LogStreamingService> servicesByRuntime;
    private final AtomicInteger backgroundJobSequence = new AtomicInteger(0);
    private final Map<Integer, BackgroundJob> backgroundJobs = new ConcurrentHashMap<>();
    private volatile boolean shellSessionActive;

    public CliRunnerService(List<LogStreamingService> services) {
        this.servicesByRuntime = indexServices(services);
    }

    @Override
    public void run(String... args) {
        exit(execute(args));
    }

    int execute(String... args) {
        if (args.length == 0) {
            printHelp();
            return 0;
        }

        String firstArg = args[0].trim().toLowerCase();

        switch (firstArg) {
            case SHELL_COMMAND -> {
                runInteractiveShell();
                return 0;
            }
            case HELP, HELP_2, H_COMMAND -> {
                printHelp();
                return 0;
            }
            default -> {
                return executeForRuntime(firstArg, Arrays.copyOfRange(args, 1, args.length));
            }
        }
    }

    protected void blockUntilInterrupted(List<Disposable> subscriptions) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            subscriptions.forEach(Disposable::dispose);
            servicesByRuntime.values().stream()
                    .distinct()
                    .forEach(LogStreamingService::stopAllStreams);
            latch.countDown();
        }));

        latch.await();
    }

    private void runInteractiveShell() {
        Scanner scanner = new Scanner(System.in);
        shellSessionActive = true;

        System.out.println(WELCOME_LINE_1);
        System.out.println(WELCOME_LINE_2);

        try {
            while (true) {
                System.out.print(BEGIN_LINE);

                if (!scanner.hasNextLine()) {
                    break;
                }

                String line = scanner.nextLine().trim();

                if (line.isBlank()) {
                    continue;
                }

                if (EXIT.equalsIgnoreCase(line) || QUIT.equalsIgnoreCase(line)) {
                    break;
                }

                if (HELP.equalsIgnoreCase(line)) {
                    printHelp();
                    continue;
                }

                String[] parts = line.split("\\s+");
                int exitCode = execute(parts);
                if (exitCode != 0) {
                    System.err.printf("Command failed with exit code %d%n", exitCode);
                }
            }
        } finally {
            shellSessionActive = false;
            disposeBackgroundJobs();
        }

        System.out.println("Bye.");
    }

    private void printHelp() {
        System.out.println("""
                Usage:
                  java -jar logguardian.jar docker list
                  java -jar logguardian.jar docker tail-all
                  java -jar logguardian.jar docker tail-one <sourceId>
                  java -jar logguardian.jar kub list
                  java -jar logguardian.jar kub tail-all
                  java -jar logguardian.jar kub tail-one <sourceId>
                  java -jar logguardian.jar shell

                Commands:
                  docker list           List running Docker containers
                  docker tail-all       Start tailing all running Docker containers
                  docker tail-one <id>  Start tailing one Docker container
                  kub list              List running Kubernetes pods
                  kub tail-all          Start tailing all running Kubernetes pods
                  kub tail-one <id>     Start tailing one Kubernetes pod
                  shell                 Start interactive shell
                  help                  Show this help
                """);
    }

    private int executeForRuntime(String runtimeKey, String[] args) {
        LogStreamingService service = servicesByRuntime.get(runtimeKey);
        if (service == null) {
            System.err.printf("Unknown runtime: %s%n%n", runtimeKey);
            printHelp();
            return 1;
        }

        if (args.length == 0) {
            System.err.printf("Missing command for runtime %s%n%n", runtimeKey);
            printHelp();
            return 1;
        }

        String command = args[0].trim().toLowerCase();
        return switch (command) {
            case LIST -> {
                listSources(service);
                yield 0;
            }
            case TAIL_ALL -> {
                tailAllSources(service);
                yield 0;
            }
            case TAIL_ONE -> {
                if (args.length < 2) {
                    System.err.println("Missing source id for tail-one command.");
                    printHelp();
                    yield 1;
                }
                tailOneSource(service, args[1]);
                yield 0;
            }
            case HELP, HELP_2, H_COMMAND -> {
                printHelp();
                yield 0;
            }
            default -> {
                System.err.printf("Unknown command: %s%n%n", command);
                printHelp();
                yield 1;
            }
        };
    }

    private void listSources(LogStreamingService service) {
        List<LogSource> sources = service.listRunningSources();

        if (sources.isEmpty()) {
            System.out.println("No running sources found.");
            return;
        }

        System.out.printf("%-70s %-30s %-25s%n", "SOURCE ID", "NAME", "STATUS");
        System.out.println("----------------------------------------------------------------------------------------------------------------------------------------");

        sources.forEach(source -> System.out.printf(
                "%-70s %-30s %-25s%n",
                source.id(),
                shorten(source.name(), 30),
                shorten(source.status(), 25)
        ));
    }

    private void tailAllSources(LogStreamingService service) {
        List<LogSource> sources = service.listRunningSources();
        if (sources.isEmpty()) {
            System.out.println("No running sources found.");
            return;
        }

        List<Disposable> subscriptions = sources.stream()
                .map(LogSource::id)
                .peek(sourceId -> System.out.printf("Current source id: %s%n", sourceId))
                .map(service::startStream)
                .toList();

        System.out.printf("Started tailing %d running sources.%n", subscriptions.size());
        if (isShellSessionActive()) {
            registerBackgroundJob("tail-all", subscriptions);
            return;
        }

        try {
            blockUntilInterrupted(subscriptions);
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for sources to stream", e);
            Thread.currentThread().interrupt();
        }
    }

    private void tailOneSource(LogStreamingService service, String sourceId) {
        System.out.printf("Started tailing source %s%n", sourceId);
        Disposable sourceStream = service.startStream(sourceId);
        if (isShellSessionActive()) {
            registerBackgroundJob("tail-one " + sourceId, List.of(sourceStream));
            return;
        }

        try {
            blockUntilInterrupted(List.of(sourceStream));
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for source stream to start", e);
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, LogStreamingService> indexServices(List<LogStreamingService> services) {
        Map<String, LogStreamingService> indexed = new LinkedHashMap<>();
        for (LogStreamingService service : services) {
            for (String key : service.runtimeKeys()) {
                String normalized = key.trim().toLowerCase();
                if (StringUtils.isBlank(normalized)) {
                    continue;
                }
                indexed.put(normalized, service);
            }
        }
        return indexed;
    }

    private String shorten(String value, int maxLength) {
        String safeValue = StringUtils.isBlank(value) ? UNKNOWN : value;
        if (safeValue.length() <= maxLength) {
            return safeValue;
        }
        return safeValue.substring(0, maxLength - 3) + "...";
    }

    protected void exit(int code) {
        System.exit(code);
    }

    protected boolean isShellSessionActive() {
        return shellSessionActive;
    }

    protected void registerBackgroundJob(String description, List<Disposable> subscriptions) {
        int jobId = backgroundJobSequence.incrementAndGet();
        BackgroundJob job = new BackgroundJob(jobId, description, subscriptions);
        backgroundJobs.put(jobId, job);
        System.out.printf("[%d] Running in background: %s%n", jobId, description);
    }

    protected void disposeBackgroundJobs() {
        Set<LogStreamingService> servicesToStop = new LinkedHashSet<>();
        List<BackgroundJob> jobsToDispose = new ArrayList<>(backgroundJobs.values());

        jobsToDispose.forEach(job -> job.subscriptions().forEach(Disposable::dispose));
        backgroundJobs.clear();

        servicesToStop.addAll(servicesByRuntime.values());
        servicesToStop.forEach(LogStreamingService::stopAllStreams);
    }

    private record BackgroundJob(int id, String description, List<Disposable> subscriptions) {
    }

}
