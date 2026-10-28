package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.calltree.CallNode;
import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.findings.DuplicateQuery;
import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.LargeResult;
import me.fertiz.queryscope.agent.findings.NPlusOne;
import me.fertiz.queryscope.agent.findings.RepeatedWrite;
import me.fertiz.queryscope.agent.findings.RequestReport;
import me.fertiz.queryscope.agent.findings.SlowMethod;
import me.fertiz.queryscope.agent.findings.SlowQuery;
import me.fertiz.queryscope.agent.findings.TooManyQueries;
import me.fertiz.queryscope.agent.http.JsonWriter;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.RequestTrace;

import java.util.List;
import java.util.Locale;

final class ReportJson {

    private ReportJson() {
    }

    static String list(List<RequestReport> reports) {
        JsonWriter json = new JsonWriter().beginArray();
        reports.forEach(report -> write(json, report));
        return json.endArray().toString();
    }

    static String event(RequestReport report) {
        JsonWriter json = new JsonWriter();
        write(json, report);
        return json.toString();
    }

    static String calls(RequestReport report, List<String> packages) {
        CallTree tree = report.trace().callTree();
        JsonWriter json = new JsonWriter().beginObject()
                .name("id").value(report.trace().id())
                .name("durationMs").value(millis(report.trace().durationNanos()))
                .name("packages").beginArray();
        packages.forEach(name -> json.value(name));
        json.endArray()
                .name("truncated").value(tree.truncated())
                .name("classesLoaded").value(report.trace().classesLoaded())
                .name("warmUp").value(report.trace().isWarmUp());
        indexes(json, "outsideQueryIndexes", tree.outsideQueryIndexes());
        json.name("methods").beginArray();
        tree.calls().forEach(node -> write(json, node));
        return json.endArray().endObject().toString();
    }

    private static void write(JsonWriter json, RequestReport report) {
        RequestTrace trace = report.trace();
        json.beginObject()
                .name("id").value(trace.id())
                .name("kind").value(trace.kind().name().toLowerCase(Locale.ROOT))
                .name("method").value(trace.method())
                .name("path").value(trace.path())
                .name("status").value(trace.status())
                .name("startedAt").value(trace.startedAt().toString())
                .name("durationMs").value(millis(trace.durationNanos()))
                .name("queries").beginArray();
        trace.queries().forEach(query -> write(json, query));
        json.endArray()
                .name("omittedQueries").value(trace.omittedQueries())
                .name("omittedMs").value(millis(trace.omittedNanos()))
                .name("findings").beginArray();
        report.findings().forEach(finding -> write(json, finding));
        json.endArray()
                .name("methods").value(trace.callTree().methods())
                .endObject();
    }

    private static void write(JsonWriter json, QueryExecution query) {
        json.beginObject()
                .name("sql").value(query.sql())
                .name("parameters").value(query.parameters())
                .name("offsetMs").value(millis(query.offsetNanos()))
                .name("durationMs").value(millis(query.durationNanos()))
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

    private static void write(JsonWriter json, Finding finding) {
        writeFindingFields(json.beginObject(), finding).endObject();
    }

    private static JsonWriter writeFindingFields(JsonWriter json, Finding finding) {
        return switch (finding.kind()) {
            case N_PLUS_ONE -> {
                NPlusOne nPlusOne = (NPlusOne) finding;
                yield repeatedStatement(json, "nPlusOne", nPlusOne.normalizedSql(), nPlusOne.callSite(),
                        nPlusOne.queryIndexes(), nPlusOne.totalNanos());
            }
            case REPEATED_WRITE -> {
                RepeatedWrite write = (RepeatedWrite) finding;
                yield repeatedStatement(json, "repeatedWrite", write.normalizedSql(), write.callSite(),
                        write.queryIndexes(), write.totalNanos());
            }
            case DUPLICATE_QUERY -> {
                DuplicateQuery duplicate = (DuplicateQuery) finding;
                json.name("type").value("duplicateQuery")
                        .name("sql").value(duplicate.normalizedSql())
                        .name("parameters").value(duplicate.parameters())
                        .name("callSite").value(duplicate.callSite())
                        .name("executions").value(duplicate.executions());
                yield indexes(json, "queryIndexes", duplicate.queryIndexes());
            }
            case SLOW_QUERY -> {
                SlowQuery slow = (SlowQuery) finding;
                yield queryFinding(json, "slowQuery", slow.normalizedSql(), slow.query())
                        .name("durationMs").value(millis(slow.query().durationNanos()))
                        .name("queryIndex").value(slow.queryIndex());
            }
            case LARGE_RESULT -> {
                LargeResult large = (LargeResult) finding;
                yield queryFinding(json, "largeResult", large.normalizedSql(), large.query())
                        .name("rows").value(large.query().rows())
                        .name("queryIndex").value(large.queryIndex());
            }
            case TOO_MANY_QUERIES -> {
                TooManyQueries tooMany = (TooManyQueries) finding;
                yield json.name("type").value("tooManyQueries")
                        .name("queries").value(tooMany.queries())
                        .name("limit").value(tooMany.limit())
                        .name("databaseMs").value(millis(tooMany.databaseNanos()))
                        .name("durationMs").value(millis(tooMany.durationNanos()));
            }
            case SLOW_METHOD -> {
                SlowMethod method = (SlowMethod) finding;
                yield json.name("type").value("slowMethod")
                        .name("method").value(method.method())
                        .name("calls").value(method.calls())
                        .name("ownMs").value(millis(method.ownNanos()))
                        .name("durationMs").value(millis(method.durationNanos()));
            }
        };
    }

    private static void write(JsonWriter json, CallNode node) {
        json.beginObject()
                .name("method").value(node.method())
                .name("calls").value(node.calls())
                .name("totalMs").value(millis(node.totalNanos()))
                .name("selfMs").value(millis(node.selfNanos()))
                .name("startedBeforeUnit").value(node.startedBeforeUnit());
        indexes(json, "queryIndexes", node.queryIndexes());
        json.name("children").beginArray();
        node.children().forEach(child -> write(json, child));
        json.endArray().endObject();
    }

    private static JsonWriter queryFinding(JsonWriter json, String type, String sql, QueryExecution query) {
        return json.name("type").value(type)
                .name("sql").value(sql)
                .name("callSite").value(query.callSite());
    }

    private static JsonWriter repeatedStatement(JsonWriter json, String type, String sql, String callSite,
                                                List<Integer> queryIndexes, long totalNanos) {
        json.name("type").value(type)
                .name("sql").value(sql)
                .name("callSite").value(callSite)
                .name("executions").value(queryIndexes.size())
                .name("totalMs").value(millis(totalNanos));
        return indexes(json, "queryIndexes", queryIndexes);
    }

    private static JsonWriter indexes(JsonWriter json, String name, List<Integer> queryIndexes) {
        json.name(name).beginArray();
        queryIndexes.forEach(index -> json.value(index));
        return json.endArray();
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
