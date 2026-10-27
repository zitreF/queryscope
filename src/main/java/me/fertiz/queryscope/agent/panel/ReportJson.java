package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.analysis.SqlNormalizer;
import me.fertiz.queryscope.agent.data.DuplicateQuery;
import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.LargeResult;
import me.fertiz.queryscope.agent.data.NPlusOne;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RepeatedWrite;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.SlowQuery;
import me.fertiz.queryscope.agent.data.TooManyQueries;

import java.util.List;
import java.util.Locale;

final class ReportJson {

    private ReportJson() {
    }

    static String of(List<RequestReport> reports) {
        JsonWriter json = new JsonWriter().beginArray();
        reports.forEach(report -> write(json, report));
        return json.endArray().toString();
    }

    static String of(RequestReport report) {
        JsonWriter json = new JsonWriter();
        write(json, report);
        return json.toString();
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
        json.endArray().name("findings").beginArray();
        report.findings().forEach(finding -> write(json, finding));
        json.endArray().endObject();
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
        json.beginObject();
        if (finding instanceof NPlusOne nPlusOne) {
            repeatedStatement(json, "nPlusOne", nPlusOne.normalizedSql(), nPlusOne.callSite(),
                    nPlusOne.queryIndexes(), nPlusOne.totalNanos());
        } else if (finding instanceof RepeatedWrite write) {
            repeatedStatement(json, "repeatedWrite", write.normalizedSql(), write.callSite(),
                    write.queryIndexes(), write.totalNanos());
        } else if (finding instanceof DuplicateQuery duplicate) {
            json.name("type").value("duplicateQuery")
                    .name("sql").value(SqlNormalizer.normalize(duplicate.sql()))
                    .name("parameters").value(duplicate.parameters())
                    .name("callSite").value(duplicate.callSite())
                    .name("executions").value(duplicate.executions());
            indexes(json, duplicate.queryIndexes());
        } else if (finding instanceof SlowQuery slow) {
            json.name("type").value("slowQuery")
                    .name("sql").value(SqlNormalizer.normalize(slow.query().sql()))
                    .name("callSite").value(slow.query().callSite())
                    .name("durationMs").value(millis(slow.query().durationNanos()))
                    .name("queryIndex").value(slow.queryIndex());
        } else if (finding instanceof LargeResult large) {
            json.name("type").value("largeResult")
                    .name("sql").value(SqlNormalizer.normalize(large.query().sql()))
                    .name("callSite").value(large.query().callSite())
                    .name("rows").value(large.query().rows())
                    .name("queryIndex").value(large.queryIndex());
        } else if (finding instanceof TooManyQueries tooMany) {
            json.name("type").value("tooManyQueries")
                    .name("queries").value(tooMany.queries())
                    .name("limit").value(tooMany.limit())
                    .name("databaseMs").value(millis(tooMany.databaseNanos()))
                    .name("durationMs").value(millis(tooMany.durationNanos()));
        }
        json.endObject();
    }

    private static void repeatedStatement(JsonWriter json, String type, String sql, String callSite,
                                          List<Integer> queryIndexes, long totalNanos) {
        json.name("type").value(type)
                .name("sql").value(sql)
                .name("callSite").value(callSite)
                .name("executions").value(queryIndexes.size())
                .name("totalMs").value(millis(totalNanos));
        indexes(json, queryIndexes);
    }

    private static void indexes(JsonWriter json, List<Integer> queryIndexes) {
        json.name("queryIndexes").beginArray();
        queryIndexes.forEach(index -> json.value(index));
        json.endArray();
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
