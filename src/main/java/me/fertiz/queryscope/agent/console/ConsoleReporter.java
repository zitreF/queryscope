package me.fertiz.queryscope.agent.console;

import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.RequestReport;
import me.fertiz.queryscope.agent.trace.RequestTrace;
import me.fertiz.queryscope.agent.trace.UnitKind;

import java.util.Locale;

public final class ConsoleReporter {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");

    public void log(RequestReport report) {
        RequestTrace trace = report.trace();
        int queries = trace.queries().size();
        String unit = trace.kind() == UnitKind.HTTP
                ? String.format(Locale.ROOT, "%s %s -> %d", trace.method(), trace.path(), trace.status())
                : trace.endpoint();
        LOGGER.log(System.Logger.Level.INFO, () -> String.format(Locale.ROOT, "%s, %d %s in %.1f ms",
                unit, queries, queries == 1 ? "query" : "queries", FindingText.millis(trace.durationNanos())));
        for (Finding finding : report.findings()) {
            LOGGER.log(System.Logger.Level.WARNING, () -> FindingText.describe(finding));
        }
    }
}
