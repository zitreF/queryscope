package me.fertiz.queryscope.agent.report;

import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.TooManyQueries;
import me.fertiz.queryscope.agent.data.UnitKind;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class SessionSummary implements Consumer<RequestReport> {

    private static final int MAX_ENDPOINTS = 500;
    private static final int MAX_FINDINGS = 200;
    private static final int SHOWN = 15;
    private static final int NAME_WIDTH = 48;
    private static final Pattern UUID_SEGMENT =
            Pattern.compile("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/|$)");
    private static final Pattern NUMBER_SEGMENT = Pattern.compile("/\\d+(?=/|$)");

    private final Clock clock;
    private final Instant startedAt;
    private final Map<String, EndpointStats> endpoints = new LinkedHashMap<>();
    private final Map<String, FindingStats> findings = new LinkedHashMap<>();
    private long units;
    private long queries;

    public SessionSummary(Clock clock) {
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    @Override
    public synchronized void accept(RequestReport report) {
        RequestTrace trace = report.trace();
        units++;
        queries += trace.queries().size();
        String name = endpointName(trace);
        EndpointStats endpoint = endpoints.get(name);
        if (endpoint == null && endpoints.size() < MAX_ENDPOINTS) {
            endpoint = new EndpointStats();
            endpoints.put(name, endpoint);
        }
        if (endpoint != null) {
            endpoint.add(report);
        }
        for (Finding finding : report.findings()) {
            String key = FindingText.identity(finding) + (finding instanceof TooManyQueries ? "|" + name : "");
            FindingStats stats = findings.get(key);
            if (stats == null && findings.size() < MAX_FINDINGS) {
                stats = new FindingStats(FindingText.describe(finding), name);
                findings.put(key, stats);
            }
            if (stats != null) {
                stats.count++;
            }
        }
    }

    public synchronized void print(PrintStream out) {
        if (units == 0) {
            return;
        }
        out.println();
        out.printf(Locale.ROOT, "QueryScope session summary: %s, %d %s, %d %s%n",
                duration(Duration.between(startedAt, clock.instant())),
                units, units == 1 ? "request or task" : "requests and tasks", queries, queries == 1 ? "query" : "queries");
        out.println();
        out.printf(Locale.ROOT, "  %-" + NAME_WIDTH + "s %6s %13s %10s %10s  %s%n",
                "Endpoint or task", "Calls", "Queries/call", "Avg time", "DB time", "Findings");
        endpoints.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, EndpointStats> entry) -> entry.getValue().databaseNanos)
                        .reversed())
                .limit(SHOWN)
                .forEach(entry -> {
                    EndpointStats stats = entry.getValue();
                    out.printf(Locale.ROOT, "  %-" + NAME_WIDTH + "s %6d %13.1f %10s %10s  %s%n",
                            shorten(entry.getKey()), stats.calls, (double) stats.queries / stats.calls,
                            time(stats.durationNanos / stats.calls), time(stats.databaseNanos),
                            stats.kinds.isEmpty() ? "-" : String.join(", ", stats.kinds));
                });
        if (endpoints.size() > SHOWN) {
            out.printf(Locale.ROOT, "  ... and %d more%n", endpoints.size() - SHOWN);
        }
        out.println();
        if (findings.isEmpty()) {
            out.println("  No findings.");
        } else {
            out.println("  Findings, most frequent first");
            findings.values().stream()
                    .sorted(Comparator.comparingLong((FindingStats stats) -> stats.count).reversed())
                    .limit(SHOWN)
                    .forEach(stats -> out.printf(Locale.ROOT, "  %5d x  %s  (%s)%n",
                            stats.count, stats.description, stats.firstSeenIn));
        }
        out.flush();
    }

    private static String endpointName(RequestTrace trace) {
        if (trace.kind() == UnitKind.TASK) {
            return "Task " + trace.path();
        }
        String path = trace.path().split("\\?", 2)[0];
        path = UUID_SEGMENT.matcher(path).replaceAll("/{uuid}");
        path = NUMBER_SEGMENT.matcher(path).replaceAll("/{id}");
        return trace.method() + " " + path;
    }

    private static String shorten(String name) {
        return name.length() <= NAME_WIDTH ? name : name.substring(0, NAME_WIDTH - 3) + "...";
    }

    private static String time(long nanos) {
        double millis = nanos / 1_000_000.0;
        return millis >= 1000
                ? String.format(Locale.ROOT, "%.2f s", millis / 1000)
                : String.format(Locale.ROOT, "%.1f ms", millis);
    }

    private static String duration(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds >= 60
                ? String.format(Locale.ROOT, "%d min %d s", seconds / 60, seconds % 60)
                : String.format(Locale.ROOT, "%d s", seconds);
    }

    private static final class EndpointStats {

        private final Set<String> kinds = new LinkedHashSet<>();
        private long calls;
        private long queries;
        private long durationNanos;
        private long databaseNanos;

        private void add(RequestReport report) {
            calls++;
            queries += report.trace().queries().size();
            durationNanos += report.trace().durationNanos();
            databaseNanos += report.trace().queries().stream().mapToLong(QueryExecution::durationNanos).sum();
            report.findings().forEach(finding -> kinds.add(FindingText.kind(finding)));
        }
    }

    private static final class FindingStats {

        private final String description;
        private final String firstSeenIn;
        private long count;

        private FindingStats(String description, String firstSeenIn) {
            this.description = description;
            this.firstSeenIn = firstSeenIn;
        }
    }
}
