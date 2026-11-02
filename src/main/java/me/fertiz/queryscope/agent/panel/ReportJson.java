package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.calltree.CallNode;
import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.findings.DuplicateQuery;
import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.FindingKind;
import me.fertiz.queryscope.agent.findings.LargeResult;
import me.fertiz.queryscope.agent.findings.NPlusOne;
import me.fertiz.queryscope.agent.findings.RepeatedWrite;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.findings.SlowMethod;
import me.fertiz.queryscope.agent.findings.SlowQuery;
import me.fertiz.queryscope.agent.findings.TooManyQueries;
import me.fertiz.queryscope.agent.http.Json;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;

final class ReportJson {

    private ReportJson() {
    }

    static String reportsToJson(List<UnitReport> reports) {
        return Json.write(Json.array(reports, ReportJson::reportJson));
    }

    static String reportToJson(UnitReport report) {
        return Json.write(reportJson(report));
    }

    static String callTreeToJson(UnitReport report, List<String> packages) {
        CallTree tree = report.trace().callTree();
        return Json.write(Json.object()
                .put("id", report.trace().id())
                .put("durationMs", toMillis(report.trace().durationNanos()))
                .set("packages", Json.strings(packages))
                .put("truncated", tree.truncated())
                .put("classesLoaded", report.trace().classesLoaded())
                .put("warmUp", report.trace().isWarmUp())
                .set("outsideQueryIndexes", Json.numbers(tree.outsideQueryIndexes()))
                .set("methods", Json.array(tree.topLevelCalls(), ReportJson::callNodeJson)));
    }

    private static ObjectNode reportJson(UnitReport report) {
        UnitTrace trace = report.trace();
        return Json.object()
                .put("id", trace.id())
                .put("kind", trace.kind().name().toLowerCase(Locale.ROOT))
                .put("method", trace.method())
                .put("path", trace.path())
                .put("status", trace.status())
                .put("port", trace.localPort())
                .put("startedAt", trace.startedAt().toString())
                .put("durationMs", toMillis(trace.durationNanos()))
                .set("queries", Json.array(trace.queries(), ReportJson::queryJson))
                .put("omittedQueries", trace.omittedQueries())
                .put("omittedMs", toMillis(trace.omittedNanos()))
                .set("findings", Json.array(report.findings(), ReportJson::findingJson))
                .put("methodCount", trace.callTree().methodCount());
    }

    private static ObjectNode queryJson(QueryExecution query) {
        ObjectNode json = Json.object()
                .put("sql", query.sql())
                .put("parameters", query.parameters())
                .put("offsetMs", toMillis(query.offsetNanos()))
                .put("durationMs", toMillis(query.durationNanos()));
        if (query.rows() == QueryExecution.NO_RESULT) {
            json.putNull("rows");
        } else {
            json.put("rows", query.rows());
        }
        return json.put("callSite", query.callSite())
                .put("failed", query.failed());
    }

    static String typeOf(FindingKind kind) {
        return switch (kind) {
            case N_PLUS_ONE -> "nPlusOne";
            case REPEATED_WRITE -> "repeatedWrite";
            case DUPLICATE_QUERY -> "duplicateQuery";
            case SLOW_QUERY -> "slowQuery";
            case LARGE_RESULT -> "largeResult";
            case TOO_MANY_QUERIES -> "tooManyQueries";
            case SLOW_METHOD -> "slowMethod";
        };
    }

    private static ObjectNode findingJson(Finding finding) {
        ObjectNode json = Json.object().put("type", typeOf(finding.kind()));
        return switch (finding.kind()) {
            case N_PLUS_ONE -> {
                NPlusOne nPlusOne = (NPlusOne) finding;
                yield putRepeatedStatement(json, nPlusOne.normalizedSql(), nPlusOne.callSite(),
                        nPlusOne.queryIndexes(), nPlusOne.totalNanos());
            }
            case REPEATED_WRITE -> {
                RepeatedWrite write = (RepeatedWrite) finding;
                yield putRepeatedStatement(json, write.normalizedSql(), write.callSite(),
                        write.queryIndexes(), write.totalNanos());
            }
            case DUPLICATE_QUERY -> {
                DuplicateQuery duplicate = (DuplicateQuery) finding;
                yield json.put("sql", duplicate.normalizedSql())
                        .put("parameters", duplicate.parameters())
                        .put("callSite", duplicate.callSite())
                        .put("executions", duplicate.executions())
                        .set("queryIndexes", Json.numbers(duplicate.queryIndexes()));
            }
            case SLOW_QUERY -> {
                SlowQuery slow = (SlowQuery) finding;
                yield putQuery(json, slow.normalizedSql(), slow.query())
                        .put("durationMs", toMillis(slow.query().durationNanos()))
                        .put("queryIndex", slow.queryIndex());
            }
            case LARGE_RESULT -> {
                LargeResult large = (LargeResult) finding;
                yield putQuery(json, large.normalizedSql(), large.query())
                        .put("rows", large.query().rows())
                        .put("queryIndex", large.queryIndex());
            }
            case TOO_MANY_QUERIES -> {
                TooManyQueries tooMany = (TooManyQueries) finding;
                yield json.put("queries", tooMany.queries())
                        .put("limit", tooMany.limit())
                        .put("databaseMs", toMillis(tooMany.databaseNanos()))
                        .put("durationMs", toMillis(tooMany.durationNanos()));
            }
            case SLOW_METHOD -> {
                SlowMethod method = (SlowMethod) finding;
                yield json.put("method", method.method())
                        .put("calls", method.calls())
                        .put("ownMs", toMillis(method.ownNanos()))
                        .put("durationMs", toMillis(method.durationNanos()));
            }
        };
    }

    private static ObjectNode callNodeJson(CallNode node) {
        return Json.object()
                .put("method", node.method())
                .put("calls", node.calls())
                .put("totalMs", toMillis(node.totalNanos()))
                .put("selfMs", toMillis(node.selfNanos()))
                .put("startedBeforeUnit", node.startedBeforeUnit())
                .set("queryIndexes", Json.numbers(node.queryIndexes()))
                .set("children", Json.array(node.children(), ReportJson::callNodeJson));
    }

    private static ObjectNode putQuery(ObjectNode json, String sql, QueryExecution query) {
        return json.put("sql", sql)
                .put("callSite", query.callSite());
    }

    private static ObjectNode putRepeatedStatement(ObjectNode json, String sql, String callSite,
                                                   List<Integer> queryIndexes, long totalNanos) {
        return json.put("sql", sql)
                .put("callSite", callSite)
                .put("executions", queryIndexes.size())
                .put("totalMs", toMillis(totalNanos))
                .set("queryIndexes", Json.numbers(queryIndexes));
    }

    static double toMillis(long nanos) {
        return Json.rounded(nanos / 1_000_000.0);
    }
}
