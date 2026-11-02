package me.fertiz.queryscope.agent.console;

import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.FindingText;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.trace.UnitTrace;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class SessionSummary implements Consumer<UnitReport> {

    private static final int MAX_ENDPOINTS = 500;
    private static final int MAX_FINDINGS = 200;
    private static final int SHOWN = 15;
    private static final int SHOWN_METHODS = 10;
    private static final int SHOWN_OWN_METHODS = 5;
    private static final int NAME_WIDTH = 48;
    private static final int METHOD_WIDTH = 100;

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
    public synchronized void accept(UnitReport report) {
        UnitTrace trace = report.trace();
        units++;
        queries += trace.queryCount();
        String name = trace.endpoint();
        EndpointStats endpoint = endpoints.get(name);
        if (endpoint == null && endpoints.size() < MAX_ENDPOINTS) {
            endpoint = new EndpointStats(name);
            endpoints.put(name, endpoint);
        }
        if (endpoint != null) {
            endpoint.add(report);
        }
        for (Finding finding : report.findings()) {
            String key = FindingText.groupKey(finding, name);
            FindingStats stats = findings.get(key);
            if (stats == null && findings.size() < MAX_FINDINGS) {
                stats = new FindingStats(FindingText.describeWithoutParameters(finding), name);
                findings.put(key, stats);
            }
            if (stats != null) {
                stats.count++;
            }
        }
    }

    public synchronized void print(PrintStream out, Profile profile, int openTasks) {
        if (units == 0 && profile.samples() == 0 && openTasks == 0) {
            return;
        }
        out.println();
        out.printf(Locale.ROOT, "QueryScope session summary: %s, %d %s, %d %s, %d CPU %s%n",
                formatSessionLength(Duration.between(startedAt, clock.instant())),
                units, units == 1 ? "request or task" : "requests and tasks", queries, queries == 1 ? "query" : "queries",
                profile.samples(), profile.samples() == 1 ? "sample" : "samples");
        if (openTasks > 0) {
            out.printf(Locale.ROOT, "  %d %s still open when the JVM stopped and %s not counted%n", openTasks,
                    openTasks == 1 ? "task was" : "tasks were", openTasks == 1 ? "is" : "are");
        }
        if (units > 0) {
            printEndpoints(out);
            printFindings(out);
        }
        if (profile.samples() > 0) {
            printHotMethods(out, profile);
        }
        if (profile.agentSamples() > 0) {
            out.printf(Locale.ROOT, "%n  QueryScope itself took %d more %s, mostly instrumenting classes at startup%n",
                    profile.agentSamples(), profile.agentSamples() == 1 ? "sample" : "samples");
        }
        out.flush();
    }

    private void printEndpoints(PrintStream out) {
        out.println();
        out.printf(Locale.ROOT, "  %-" + NAME_WIDTH + "s %6s %13s %10s %10s  %s%n",
                "Endpoint or task", "Calls", "Queries/call", "Avg time", "DB time", "Findings");
        endpoints.values().stream()
                .sorted(Comparator.comparingLong((EndpointStats stats) -> stats.databaseNanos).reversed())
                .limit(SHOWN)
                .forEach(stats -> out.printf(Locale.ROOT, "  %-" + NAME_WIDTH + "s %6d %13.1f %10s %10s  %s%n",
                        shorten(stats.name), stats.calls, (double) stats.queries / stats.calls,
                        formatDuration(stats.durationNanos / stats.calls), formatDuration(stats.databaseNanos),
                        stats.badges.isEmpty() ? "-" : String.join(", ", stats.badges)));
        if (endpoints.size() > SHOWN) {
            out.printf(Locale.ROOT, "  ... and %d more%n", endpoints.size() - SHOWN);
        }
    }

    private void printFindings(PrintStream out) {
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
    }

    private static void printHotMethods(PrintStream out, Profile profile) {
        double samples = profile.samples();
        out.println();
        out.println("  Hottest methods by CPU samples");
        out.printf(Locale.ROOT, "  %6s %6s  %s%n", "Self", "Total", "Method");
        profile.hot().stream()
                .filter(method -> method.self() > 0)
                .sorted(Comparator.comparingInt(Profile.HotMethod::self).reversed())
                .limit(SHOWN_METHODS)
                .forEach(method -> out.printf(Locale.ROOT, "  %5.1f%% %5.1f%%  %s%s%n",
                        method.self() * 100 / samples, method.total() * 100 / samples, shortenMethod(method.method()),
                        method.app() ? "  (your code)" : ""));
        List<Profile.HotMethod> yours = profile.hot().stream()
                .filter(method -> method.selfWithLibraries() > 0)
                .sorted(Comparator.comparingInt(Profile.HotMethod::selfWithLibraries).reversed())
                .limit(SHOWN_OWN_METHODS)
                .toList();
        if (!yours.isEmpty()) {
            out.println();
            out.println("  Your methods by CPU time in them and in the library code they call");
            yours.forEach(method -> out.printf(Locale.ROOT, "  %5.1f%%  %s%n",
                    method.selfWithLibraries() * 100 / samples, shortenMethod(method.method())));
        }
    }

    private static String shortenMethod(String method) {
        return method.length() <= METHOD_WIDTH ? method : "..." + method.substring(method.length() - METHOD_WIDTH + 3);
    }

    private static String shorten(String name) {
        return name.length() <= NAME_WIDTH ? name : name.substring(0, NAME_WIDTH - 3) + "...";
    }

    static String formatDuration(long nanos) {
        double millis = nanos / 1_000_000.0;
        return millis >= 1000
                ? String.format(Locale.ROOT, "%.2f s", millis / 1000)
                : String.format(Locale.ROOT, "%.1f ms", millis);
    }

    private static String formatSessionLength(Duration duration) {
        long seconds = duration.toSeconds();
        return seconds >= 60
                ? String.format(Locale.ROOT, "%d min %d s", seconds / 60, seconds % 60)
                : String.format(Locale.ROOT, "%d s", seconds);
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
