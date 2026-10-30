package me.fertiz.queryscope.agent.console;

import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.methodtrace.TracedMethod;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.agent.trace.UnitTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AiReport {

    private static final int SHOWN_FINDINGS = 20;
    private static final int SHOWN_ENDPOINTS = 15;
    private static final int SHOWN_METHODS = 15;
    private static final int SHOWN_ENDPOINTS_PER_FINDING = 3;

    private AiReport() {
    }

    public static String markdown(List<UnitReport> newestFirst, List<String> packages, Profile profile,
                                  String sampleSource, List<TracedMethod.Snapshot> traces) {
        StringBuilder out = new StringBuilder("# QueryScope report\n\n");
        out.append(packages.isEmpty()
                        ? "QueryScope could not tell the application's packages, so it knows no code as yours."
                        : "The application's code is in " + String.join(", ", packages) + ".")
                .append(" This report covers the ").append(newestFirst.size())
                .append(newestFirst.size() == 1 ? " request or task" : " requests and tasks")
                .append(" QueryScope keeps, newest first in its panel.\n");
        writeFindings(out, newestFirst);
        writeEndpoints(out, newestFirst);
        writeHotMethods(out, profile, sampleSource);
        writeTraces(out, traces);
        return out.toString();
    }

    public static String requestList(List<UnitReport> newestFirst, int limit, boolean onlyWithFindings) {
        StringBuilder out = new StringBuilder();
        newestFirst.stream()
                .filter(report -> !onlyWithFindings || !report.findings().isEmpty())
                .limit(limit)
                .forEach(report -> {
                    UnitTrace trace = report.trace();
                    out.append("#").append(trace.id()).append(' ')
                            .append(trace.kind() == UnitKind.HTTP ? trace.method() + " " + trace.path() + " -> "
                                    + trace.status() : "Task " + trace.path())
                            .append(" · ").append(SessionSummary.formatDuration(trace.durationNanos()))
                            .append(" · ").append(trace.queryCount())
                            .append(trace.queryCount() == 1 ? " query" : " queries");
                    if (!report.findings().isEmpty()) {
                        Set<String> badges = new LinkedHashSet<>();
                        report.findings().forEach(finding -> badges.add(FindingText.badge(finding)));
                        out.append(" · ").append(String.join(", ", badges));
                    }
                    out.append('\n');
                });
        return out.length() == 0 ? "No requests or tasks yet.\n" : out.toString();
    }

    private static void writeFindings(StringBuilder out, List<UnitReport> reports) {
        Map<String, FindingGroup> groups = new LinkedHashMap<>();
        for (UnitReport report : reports) {
            String endpoint = report.trace().endpoint();
            for (Finding finding : report.findings()) {
                groups.computeIfAbsent(FindingText.groupKey(finding, endpoint),
                                key -> new FindingGroup(finding, report.trace().id()))
                        .seenIn(endpoint);
            }
        }
        out.append("\n## Findings, most frequent first\n\n");
        if (groups.isEmpty()) {
            out.append("No findings in these requests and tasks.\n");
            return;
        }
        List<FindingGroup> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparingInt(FindingGroup::units).reversed());
        int number = 1;
        for (FindingGroup group : sorted.subList(0, Math.min(SHOWN_FINDINGS, sorted.size()))) {
            out.append(number++).append(". ").append(FindingText.describeWithoutParameters(group.finding))
                    .append("\n   - Seen in ").append(group.units()).append(group.units() == 1 ? " unit: " : " units: ")
                    .append(group.endpointsText())
                    .append("\n   - Newest example: request or task ").append(group.exampleId).append('\n');
        }
        if (sorted.size() > SHOWN_FINDINGS) {
            out.append("\n").append(sorted.size() - SHOWN_FINDINGS).append(" more findings are not listed.\n");
        }
    }

    private static void writeEndpoints(StringBuilder out, List<UnitReport> reports) {
        Map<String, EndpointStats> endpoints = new LinkedHashMap<>();
        for (UnitReport report : reports) {
            endpoints.computeIfAbsent(report.trace().endpoint(), EndpointStats::new).add(report);
        }
        out.append("\n## Endpoints and tasks by time in the database\n\n");
        if (endpoints.isEmpty()) {
            out.append("No requests or tasks yet.\n");
            return;
        }
        out.append("| Endpoint or task | Calls | Queries per call | Average time | Time in database, all calls")
                .append(" | Findings |\n|---|---|---|---|---|---|\n");
        endpoints.values().stream()
                .sorted(Comparator.comparingLong((EndpointStats stats) -> stats.databaseNanos).reversed())
                .limit(SHOWN_ENDPOINTS)
                .forEach(stats -> out.append("| ").append(tableCell(stats.name)).append(" | ").append(stats.calls)
                        .append(" | ").append(String.format(Locale.ROOT, "%.1f", (double) stats.queries / stats.calls))
                        .append(" | ").append(SessionSummary.formatDuration(stats.durationNanos / stats.calls))
                        .append(" | ").append(SessionSummary.formatDuration(stats.databaseNanos))
                        .append(" | ").append(stats.badges.isEmpty() ? "-" : String.join(", ", stats.badges))
                        .append(" |\n"));
    }

    private static void writeHotMethods(StringBuilder out, Profile profile, String sampleSource) {
        out.append("\n## Hot methods\n\n");
        if (profile.samples() == 0) {
            out.append("No CPU samples yet.\n");
            return;
        }
        double samples = profile.samples();
        out.append(profile.samples()).append(" CPU samples from ").append(sampleSource)
                .append(". Self is the share of samples in the method's own code, total includes what it calls.\n\n")
                .append("| Method | Self | Total | Your code |\n|---|---|---|---|\n");
        profile.hot().stream()
                .filter(method -> method.self() > 0)
                .sorted(Comparator.comparingInt(Profile.HotMethod::self).reversed())
                .limit(SHOWN_METHODS)
                .forEach(method -> out.append("| `").append(method.method()).append("` | ")
                        .append(percent(method.self(), samples)).append(" | ").append(percent(method.total(), samples))
                        .append(" | ").append(method.app() ? "yes" : "no").append(" |\n"));
        List<Profile.HotMethod> yours = profile.hot().stream()
                .filter(method -> method.selfWithLibraries() > 0)
                .sorted(Comparator.comparingInt(Profile.HotMethod::selfWithLibraries).reversed())
                .limit(SHOWN_METHODS)
                .toList();
        if (!yours.isEmpty()) {
            out.append("\nYour methods by the samples taken in them and in the library code they call:\n\n");
            yours.forEach(method -> out.append("- ").append(percent(method.selfWithLibraries(), samples))
                    .append(" `").append(method.method()).append("`\n"));
        }
    }

    private static void writeTraces(StringBuilder out, List<TracedMethod.Snapshot> traces) {
        if (traces.isEmpty()) {
            return;
        }
        out.append("\n## Traced methods\n\n| Method | Calls | Failed | Average | p95 | Max |\n")
                .append("|---|---|---|---|---|---|\n");
        for (TracedMethod.Snapshot trace : traces) {
            out.append("| `").append(trace.method()).append("` | ").append(trace.calls()).append(" | ")
                    .append(trace.failures()).append(" | ").append(SessionSummary.formatDuration(trace.averageNanos()))
                    .append(" | ").append(SessionSummary.formatDuration(trace.p95Nanos()))
                    .append(" | ").append(SessionSummary.formatDuration(trace.maxNanos())).append(" |\n");
        }
    }

    private static String percent(int part, double whole) {
        return String.format(Locale.ROOT, "%.1f%%", part * 100 / whole);
    }

    private static String tableCell(String text) {
        return text.replace("|", "\\|");
    }

    private static final class FindingGroup {

        private final Finding finding;
        private final long exampleId;
        private final Map<String, Integer> endpoints = new LinkedHashMap<>();

        private FindingGroup(Finding finding, long exampleId) {
            this.finding = finding;
            this.exampleId = exampleId;
        }

        private void seenIn(String endpoint) {
            endpoints.merge(endpoint, 1, Integer::sum);
        }

        private int units() {
            return endpoints.values().stream().mapToInt(Integer::intValue).sum();
        }

        private String endpointsText() {
            List<String> shown = endpoints.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(SHOWN_ENDPOINTS_PER_FINDING)
                    .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                    .toList();
            int hidden = endpoints.size() - shown.size();
            return String.join(", ", shown) + (hidden > 0 ? " and " + hidden + " more" : "");
        }
    }
}
