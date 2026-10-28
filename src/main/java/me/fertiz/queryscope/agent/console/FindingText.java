package me.fertiz.queryscope.agent.console;

import me.fertiz.queryscope.agent.calltree.CallSites;
import me.fertiz.queryscope.agent.findings.DuplicateQuery;
import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.LargeResult;
import me.fertiz.queryscope.agent.findings.NPlusOne;
import me.fertiz.queryscope.agent.findings.RepeatedWrite;
import me.fertiz.queryscope.agent.findings.SlowMethod;
import me.fertiz.queryscope.agent.findings.SlowQuery;
import me.fertiz.queryscope.agent.findings.TooManyQueries;

import java.util.Locale;
import java.util.Map;

final class FindingText {

    private static final int MAX_SQL_LENGTH = 160;
    private static final Map<Class<? extends Finding>, String> BADGES = Map.of(
            NPlusOne.class, "N+1",
            RepeatedWrite.class, "Writes",
            DuplicateQuery.class, "Duplicate",
            SlowQuery.class, "Slow",
            LargeResult.class, "Rows",
            SlowMethod.class, "Code",
            TooManyQueries.class, "Queries");

    private FindingText() {
    }

    static String describe(Finding finding) {
        if (finding instanceof DuplicateQuery duplicate) {
            return String.format(Locale.ROOT, "Duplicate query: %d x %s with %s%s", duplicate.executions(),
                    shorten(duplicate.normalizedSql()), duplicate.parameters(), location(duplicate.callSite()));
        }
        return describeWithoutParameters(finding);
    }

    static String describeWithoutParameters(Finding finding) {
        if (finding instanceof NPlusOne nPlusOne) {
            return String.format(Locale.ROOT, "N+1: %d x %s%s", nPlusOne.executions(),
                    shorten(nPlusOne.normalizedSql()), location(nPlusOne.callSite()));
        }
        if (finding instanceof RepeatedWrite write) {
            return String.format(Locale.ROOT, "Unbatched writes: %d x %s%s", write.executions(),
                    shorten(write.normalizedSql()), location(write.callSite()));
        }
        if (finding instanceof DuplicateQuery duplicate) {
            return String.format(Locale.ROOT, "Duplicate query: %d x %s%s", duplicate.executions(),
                    shorten(duplicate.normalizedSql()), location(duplicate.callSite()));
        }
        if (finding instanceof SlowQuery slow) {
            return String.format(Locale.ROOT, "Slow query: %.1f ms %s%s", millis(slow.query().durationNanos()),
                    shorten(slow.normalizedSql()), location(slow.query().callSite()));
        }
        if (finding instanceof LargeResult large) {
            return String.format(Locale.ROOT, "Large result: %d rows from %s%s", large.query().rows(),
                    shorten(large.normalizedSql()), location(large.query().callSite()));
        }
        if (finding instanceof SlowMethod slow) {
            return String.format(Locale.ROOT, "Slow method: %s spent %.1f ms in its own code outside SQL over %d %s,"
                            + " %.0f%% of the time", CallSites.shortName(slow.method()), millis(slow.ownNanos()),
                    slow.calls(), slow.calls() == 1 ? "call" : "calls", share(slow.ownNanos(), slow.durationNanos()));
        }
        TooManyQueries tooMany = (TooManyQueries) finding;
        return String.format(Locale.ROOT, "Too many queries: %d (limit %d), %.0f%% of the time in the database",
                tooMany.queries(), tooMany.limit(), share(tooMany.databaseNanos(), tooMany.durationNanos()));
    }

    static String badge(Finding finding) {
        return BADGES.get(finding.getClass());
    }

    static String groupKey(Finding finding, String endpoint) {
        if (finding instanceof NPlusOne nPlusOne) {
            return "nPlusOne|" + nPlusOne.normalizedSql() + "|" + nPlusOne.callSite();
        }
        if (finding instanceof RepeatedWrite write) {
            return "write|" + write.normalizedSql() + "|" + write.callSite();
        }
        if (finding instanceof DuplicateQuery duplicate) {
            return "duplicate|" + duplicate.normalizedSql() + "|" + duplicate.callSite();
        }
        if (finding instanceof SlowQuery slow) {
            return "slow|" + slow.normalizedSql() + "|" + slow.query().callSite();
        }
        if (finding instanceof LargeResult large) {
            return "rows|" + large.normalizedSql() + "|" + large.query().callSite();
        }
        if (finding instanceof SlowMethod slow) {
            return "method|" + slow.method();
        }
        return "queries|" + endpoint;
    }

    static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double share(long part, long whole) {
        return whole == 0 ? 0 : Math.min(100, part * 100.0 / whole);
    }

    private static String shorten(String sql) {
        return sql.length() <= MAX_SQL_LENGTH ? sql : sql.substring(0, MAX_SQL_LENGTH) + "...";
    }

    private static String location(String callSite) {
        return callSite == null ? "" : " at " + callSite;
    }
}
