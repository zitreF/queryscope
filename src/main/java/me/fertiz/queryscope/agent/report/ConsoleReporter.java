package me.fertiz.queryscope.agent.report;

import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.UnitKind;

import java.util.Locale;
import java.util.function.Consumer;

public final class ConsoleReporter implements Consumer<RequestReport> {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");

    @Override
    public void accept(RequestReport report) {
        RequestTrace trace = report.trace();
        int queries = trace.queries().size();
        String unit = trace.kind() == UnitKind.HTTP
                ? String.format(Locale.ROOT, "%s %s -> %d", trace.method(), trace.path(), trace.status())
                : "Task " + trace.path();
        LOGGER.log(System.Logger.Level.INFO, () -> String.format(Locale.ROOT, "%s, %d %s in %.1f ms",
                unit, queries, queries == 1 ? "query" : "queries", FindingText.millis(trace.durationNanos())));
        for (Finding finding : report.findings()) {
            LOGGER.log(System.Logger.Level.WARNING, () -> FindingText.describe(finding));
        }
    }
}
