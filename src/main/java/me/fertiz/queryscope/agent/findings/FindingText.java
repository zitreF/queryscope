package me.fertiz.queryscope.agent.findings;

import me.fertiz.queryscope.agent.appcode.CallSites;

import java.util.Locale;

public final class FindingText {

    private static final int MAX_SQL_LENGTH = 160;

    private FindingText() {
    }

    public static String describe(Finding finding) {
        if (finding instanceof DuplicateQuery duplicate) {
            return String.format(Locale.ROOT, "Duplicate query: %d x %s with %s%s", duplicate.executions(),
                    shorten(duplicate.normalizedSql()), duplicate.parameters(), atCallSite(duplicate.callSite()));
        }
        return describeWithoutParameters(finding);
    }

    public static String describeWithoutParameters(Finding finding) {
        return switch (finding.kind()) {
            case N_PLUS_ONE -> {
                NPlusOne nPlusOne = (NPlusOne) finding;
                yield String.format(Locale.ROOT, "N+1: %d x %s%s", nPlusOne.executions(),
                        shorten(nPlusOne.normalizedSql()), atCallSite(nPlusOne.callSite()));
            }
            case REPEATED_WRITE -> {
                RepeatedWrite write = (RepeatedWrite) finding;
                yield String.format(Locale.ROOT, "Unbatched writes: %d x %s%s", write.executions(),
                        shorten(write.normalizedSql()), atCallSite(write.callSite()));
            }
            case DUPLICATE_QUERY -> {
                DuplicateQuery duplicate = (DuplicateQuery) finding;
                yield String.format(Locale.ROOT, "Duplicate query: %d x %s%s", duplicate.executions(),
                        shorten(duplicate.normalizedSql()), atCallSite(duplicate.callSite()));
            }
            case SLOW_QUERY -> {
                SlowQuery slow = (SlowQuery) finding;
                yield String.format(Locale.ROOT, "Slow query: %.1f ms %s%s", toMillis(slow.query().durationNanos()),
                        shorten(slow.normalizedSql()), atCallSite(slow.query().callSite()));
            }
            case LARGE_RESULT -> {
                LargeResult large = (LargeResult) finding;
                yield String.format(Locale.ROOT, "Large result: %d rows from %s%s", large.query().rows(),
                        shorten(large.normalizedSql()), atCallSite(large.query().callSite()));
            }
            case TOO_MANY_QUERIES -> {
                TooManyQueries tooMany = (TooManyQueries) finding;
                yield String.format(Locale.ROOT, "Too many queries: %d (limit %d), %.0f%% of the time in the database",
                        tooMany.queries(), tooMany.limit(),
                        percentOf(tooMany.databaseNanos(), tooMany.durationNanos()));
            }
            case SLOW_METHOD -> {
                SlowMethod slow = (SlowMethod) finding;
                yield String.format(Locale.ROOT, "Slow method: %s spent %.1f ms in its own code outside SQL over %d %s,"
                                + " %.0f%% of the time", CallSites.classAndMethod(slow.method()),
                        toMillis(slow.ownNanos()), slow.calls(), slow.calls() == 1 ? "call" : "calls",
                        percentOf(slow.ownNanos(), slow.durationNanos()));
            }
        };
    }

    public static String badge(Finding finding) {
        return badge(finding.kind());
    }

    public static String badge(FindingKind kind) {
        return switch (kind) {
            case N_PLUS_ONE -> "N+1";
            case REPEATED_WRITE -> "Writes";
            case DUPLICATE_QUERY -> "Duplicate";
            case SLOW_QUERY -> "Slow";
            case LARGE_RESULT -> "Rows";
            case TOO_MANY_QUERIES -> "Queries";
            case SLOW_METHOD -> "Code";
        };
    }

    public static String groupKey(Finding finding, String endpoint) {
        String detail = switch (finding.kind()) {
            case N_PLUS_ONE -> {
                NPlusOne nPlusOne = (NPlusOne) finding;
                yield nPlusOne.normalizedSql() + "|" + nPlusOne.callSite();
            }
            case REPEATED_WRITE -> {
                RepeatedWrite write = (RepeatedWrite) finding;
                yield write.normalizedSql() + "|" + write.callSite();
            }
            case DUPLICATE_QUERY -> {
                DuplicateQuery duplicate = (DuplicateQuery) finding;
                yield duplicate.normalizedSql() + "|" + duplicate.callSite();
            }
            case SLOW_QUERY -> {
                SlowQuery slow = (SlowQuery) finding;
                yield slow.normalizedSql() + "|" + slow.query().callSite();
            }
            case LARGE_RESULT -> {
                LargeResult large = (LargeResult) finding;
                yield large.normalizedSql() + "|" + large.query().callSite();
            }
            case TOO_MANY_QUERIES -> endpoint;
            case SLOW_METHOD -> ((SlowMethod) finding).method();
        };
        return finding.kind() + "|" + detail;
    }

    public static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double percentOf(long part, long whole) {
        return whole == 0 ? 0 : Math.min(100, part * 100.0 / whole);
    }

    private static String shorten(String sql) {
        return sql.length() <= MAX_SQL_LENGTH ? sql : sql.substring(0, MAX_SQL_LENGTH) + "...";
    }

    private static String atCallSite(String callSite) {
        return callSite == null ? "" : " at " + callSite;
    }
}
