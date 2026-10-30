package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.calltree.CallNode;
import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.findings.DuplicateQuery;
import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.LargeResult;
import me.fertiz.queryscope.agent.findings.NPlusOne;
import me.fertiz.queryscope.agent.findings.RepeatedWrite;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.findings.SlowMethod;
import me.fertiz.queryscope.agent.findings.SlowQuery;
import me.fertiz.queryscope.agent.findings.TooManyQueries;
import me.fertiz.queryscope.agent.http.JsonWriter;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.UnitTrace;

import java.util.List;
import java.util.Locale;

final class ReportJson {

    private ReportJson() {
    }

    static String reportsToJson(List<UnitReport> reports) {
        JsonWriter json = new JsonWriter().beginArray();
        reports.forEach(report -> writeReport(json, report));
        return json.endArray().toString();
    }

    static String reportToJson(UnitReport report) {
        JsonWriter json = new JsonWriter();
        writeReport(json, report);
        return json.toString();
    }

    static String callTreeToJson(UnitReport report, List<String> packages) {
        CallTree tree = report.trace().callTree();
        JsonWriter json = new JsonWriter().beginObject()
                .name("id").value(report.trace().id())
                .name("durationMs").value(toMillis(report.trace().durationNanos()))
                .name("packages").beginArray();
        packages.forEach(name -> json.value(name));
        json.endArray()
                .name("truncated").value(tree.truncated())
                .name("classesLoaded").value(report.trace().classesLoaded())
                .name("warmUp").value(report.trace().isWarmUp());
        writeIndexes(json, "outsideQueryIndexes", tree.outsideQueryIndexes());
        json.name("methods").beginArray();
        tree.topLevelCalls().forEach(node -> writeCallNode(json, node));
        return json.endArray().endObject().toString();
    }

    private static void writeReport(JsonWriter json, UnitReport report) {
        UnitTrace trace = report.trace();
        json.beginObject()
                .name("id").value(trace.id())
                .name("kind").value(trace.kind().name().toLowerCase(Locale.ROOT))
                .name("method").value(trace.method())
                .name("path").value(trace.path())
                .name("status").value(trace.status())
                .name("startedAt").value(trace.startedAt().toString())
                .name("durationMs").value(toMillis(trace.durationNanos()))
                .name("queries").beginArray();
        trace.queries().forEach(query -> writeQuery(json, query));
        json.endArray()
                .name("omittedQueries").value(trace.omittedQueries())
                .name("omittedMs").value(toMillis(trace.omittedNanos()))
                .name("findings").beginArray();
        report.findings().forEach(finding -> writeFinding(json, finding));
        json.endArray()
                .name("methodCount").value(trace.callTree().methodCount())
                .endObject();
    }

    private static void writeQuery(JsonWriter json, QueryExecution query) {
        json.beginObject()
                .name("sql").value(query.sql())
                .name("parameters").value(query.parameters())
                .name("offsetMs").value(toMillis(query.offsetNanos()))
                .name("durationMs").value(toMillis(query.durationNanos()))
                .name("rows");
        if (query.rows() == QueryExecution.NO_RESULT) {
            json.nullValue();
        } else {
            json.value(query.rows());
        }
        json.name("callSite").value(query.callSite())
                .name("failed").value(query.failed())
                .endObject();
    }

    private static void writeFinding(JsonWriter json, Finding finding) {
        writeFindingFields(json.beginObject(), finding).endObject();
    }

    private static JsonWriter writeFindingFields(JsonWriter json, Finding finding) {
        return switch (finding.kind()) {
            case N_PLUS_ONE -> {
                NPlusOne nPlusOne = (NPlusOne) finding;
                yield writeRepeatedStatementFields(json, "nPlusOne", nPlusOne.normalizedSql(), nPlusOne.callSite(),
                        nPlusOne.queryIndexes(), nPlusOne.totalNanos());
            }
            case REPEATED_WRITE -> {
                RepeatedWrite write = (RepeatedWrite) finding;
                yield writeRepeatedStatementFields(json, "repeatedWrite", write.normalizedSql(), write.callSite(),
                        write.queryIndexes(), write.totalNanos());
            }
            case DUPLICATE_QUERY -> {
                DuplicateQuery duplicate = (DuplicateQuery) finding;
                json.name("type").value("duplicateQuery")
                        .name("sql").value(duplicate.normalizedSql())
                        .name("parameters").value(duplicate.parameters())
                        .name("callSite").value(duplicate.callSite())
                        .name("executions").value(duplicate.executions());
                yield writeIndexes(json, "queryIndexes", duplicate.queryIndexes());
            }
            case SLOW_QUERY -> {
                SlowQuery slow = (SlowQuery) finding;
                yield writeQueryFindingFields(json, "slowQuery", slow.normalizedSql(), slow.query())
                        .name("durationMs").value(toMillis(slow.query().durationNanos()))
                        .name("queryIndex").value(slow.queryIndex());
            }
            case LARGE_RESULT -> {
                LargeResult large = (LargeResult) finding;
                yield writeQueryFindingFields(json, "largeResult", large.normalizedSql(), large.query())
                        .name("rows").value(large.query().rows())
                        .name("queryIndex").value(large.queryIndex());
            }
            case TOO_MANY_QUERIES -> {
                TooManyQueries tooMany = (TooManyQueries) finding;
                yield json.name("type").value("tooManyQueries")
                        .name("queries").value(tooMany.queries())
                        .name("limit").value(tooMany.limit())
                        .name("databaseMs").value(toMillis(tooMany.databaseNanos()))
                        .name("durationMs").value(toMillis(tooMany.durationNanos()));
            }
            case SLOW_METHOD -> {
                SlowMethod method = (SlowMethod) finding;
                yield json.name("type").value("slowMethod")
                        .name("method").value(method.method())
                        .name("calls").value(method.calls())
                        .name("ownMs").value(toMillis(method.ownNanos()))
                        .name("durationMs").value(toMillis(method.durationNanos()));
            }
        };
    }

    private static void writeCallNode(JsonWriter json, CallNode node) {
        json.beginObject()
                .name("method").value(node.method())
                .name("calls").value(node.calls())
                .name("totalMs").value(toMillis(node.totalNanos()))
                .name("selfMs").value(toMillis(node.selfNanos()))
                .name("startedBeforeUnit").value(node.startedBeforeUnit());
        writeIndexes(json, "queryIndexes", node.queryIndexes());
        json.name("children").beginArray();
        node.children().forEach(child -> writeCallNode(json, child));
        json.endArray().endObject();
    }

    private static JsonWriter writeQueryFindingFields(JsonWriter json, String type, String sql, QueryExecution query) {
        return json.name("type").value(type)
                .name("sql").value(sql)
                .name("callSite").value(query.callSite());
    }

    private static JsonWriter writeRepeatedStatementFields(JsonWriter json, String type, String sql, String callSite,
                                                List<Integer> queryIndexes, long totalNanos) {
        json.name("type").value(type)
                .name("sql").value(sql)
                .name("callSite").value(callSite)
                .name("executions").value(queryIndexes.size())
                .name("totalMs").value(toMillis(totalNanos));
        return writeIndexes(json, "queryIndexes", queryIndexes);
    }

    private static JsonWriter writeIndexes(JsonWriter json, String name, List<Integer> queryIndexes) {
        json.name(name).beginArray();
        queryIndexes.forEach(index -> json.value(index));
        return json.endArray();
    }

    private static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
