package me.fertiz.queryscope.agent.report;

import me.fertiz.queryscope.agent.analysis.SqlNormalizer;
import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.NPlusOne;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.SlowQuery;

import java.util.Locale;
import java.util.function.Consumer;

public final class ConsoleReporter implements Consumer<RequestReport> {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final int MAX_SQL_LENGTH = 160;

    @Override
    public void accept(RequestReport report) {
        RequestTrace trace = report.trace();
        int queries = trace.queries().size();
        LOGGER.log(System.Logger.Level.INFO, () -> String.format(Locale.ROOT, "%s %s -> %d, %d %s in %.1f ms",
                trace.method(), trace.path(), trace.status(), queries, queries == 1 ? "query" : "queries",
                millis(trace.durationNanos())));
        for (Finding finding : report.findings()) {
            LOGGER.log(System.Logger.Level.WARNING, () -> describe(finding));
        }
    }

    private static String describe(Finding finding) {
        if (finding instanceof NPlusOne nPlusOne) {
            return String.format(Locale.ROOT, "N+1: %d x %s%s", nPlusOne.executions(),
                    shorten(nPlusOne.normalizedSql()), location(nPlusOne.callSite()));
        }
        SlowQuery slow = (SlowQuery) finding;
        return String.format(Locale.ROOT, "Slow query: %.1f ms %s%s", millis(slow.query().durationNanos()),
                shorten(SqlNormalizer.normalize(slow.query().sql())), location(slow.query().callSite()));
    }

    private static String shorten(String sql) {
        return sql.length() <= MAX_SQL_LENGTH ? sql : sql.substring(0, MAX_SQL_LENGTH) + "...";
    }

    private static String location(String callSite) {
        return callSite == null ? "" : " at " + callSite;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
