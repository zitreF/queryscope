package me.fertiz.queryscope.agent.report;

import me.fertiz.queryscope.agent.analysis.SqlNormalizer;
import me.fertiz.queryscope.agent.data.DuplicateQuery;
import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.LargeResult;
import me.fertiz.queryscope.agent.data.NPlusOne;
import me.fertiz.queryscope.agent.data.RepeatedWrite;
import me.fertiz.queryscope.agent.data.SlowQuery;
import me.fertiz.queryscope.agent.data.TooManyQueries;

import java.util.Locale;

final class FindingText {

    private static final int MAX_SQL_LENGTH = 160;

    private FindingText() {
    }

    static String describe(Finding finding) {
        if (finding instanceof NPlusOne nPlusOne) {
            return String.format(Locale.ROOT, "N+1: %d x %s%s", nPlusOne.executions(),
                    shorten(nPlusOne.normalizedSql()), location(nPlusOne.callSite()));
        }
        if (finding instanceof RepeatedWrite write) {
            return String.format(Locale.ROOT, "Unbatched writes: %d x %s%s", write.executions(),
                    shorten(write.normalizedSql()), location(write.callSite()));
        }
        if (finding instanceof DuplicateQuery duplicate) {
            return String.format(Locale.ROOT, "Duplicate query: %d x %s with %s%s", duplicate.executions(),
                    shorten(SqlNormalizer.normalize(duplicate.sql())), duplicate.parameters(),
                    location(duplicate.callSite()));
        }
        if (finding instanceof SlowQuery slow) {
            return String.format(Locale.ROOT, "Slow query: %.1f ms %s%s", millis(slow.query().durationNanos()),
                    shorten(SqlNormalizer.normalize(slow.query().sql())), location(slow.query().callSite()));
        }
        if (finding instanceof LargeResult large) {
            return String.format(Locale.ROOT, "Large result: %d rows from %s%s", large.query().rows(),
                    shorten(SqlNormalizer.normalize(large.query().sql())), location(large.query().callSite()));
        }
        TooManyQueries tooMany = (TooManyQueries) finding;
        return String.format(Locale.ROOT, "Too many queries: %d (limit %d), %.0f%% of the time in the database",
                tooMany.queries(), tooMany.limit(), share(tooMany.databaseNanos(), tooMany.durationNanos()));
    }

    static String kind(Finding finding) {
        if (finding instanceof NPlusOne) {
            return "N+1";
        }
        if (finding instanceof RepeatedWrite) {
            return "Writes";
        }
        if (finding instanceof DuplicateQuery) {
            return "Duplicate";
        }
        if (finding instanceof SlowQuery) {
            return "Slow";
        }
        if (finding instanceof LargeResult) {
            return "Rows";
        }
        return "Queries";
    }

    static String identity(Finding finding) {
        if (finding instanceof NPlusOne nPlusOne) {
            return "nPlusOne|" + nPlusOne.normalizedSql() + "|" + nPlusOne.callSite();
        }
        if (finding instanceof RepeatedWrite write) {
            return "write|" + write.normalizedSql() + "|" + write.callSite();
        }
        if (finding instanceof DuplicateQuery duplicate) {
            return "duplicate|" + SqlNormalizer.normalize(duplicate.sql()) + "|" + duplicate.callSite();
        }
        if (finding instanceof SlowQuery slow) {
            return "slow|" + SqlNormalizer.normalize(slow.query().sql()) + "|" + slow.query().callSite();
        }
        if (finding instanceof LargeResult large) {
            return "rows|" + SqlNormalizer.normalize(large.query().sql()) + "|" + large.query().callSite();
        }
        return "queries";
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
