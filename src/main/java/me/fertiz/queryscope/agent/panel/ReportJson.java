package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.analysis.SqlNormalizer;
import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.NPlusOne;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.SlowQuery;

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
                .name("offsetMs").value(millis(query.offsetNanos()))
                .name("durationMs").value(millis(query.durationNanos()))
                .name("callSite").value(query.callSite())
                .name("failed").value(query.failed())
                .endObject();
    }

    private static void write(JsonWriter json, Finding finding) {
        if (finding instanceof NPlusOne nPlusOne) {
            json.beginObject()
                    .name("type").value("nPlusOne")
                    .name("sql").value(nPlusOne.normalizedSql())
                    .name("callSite").value(nPlusOne.callSite())
                    .name("executions").value(nPlusOne.executions())
                    .name("totalMs").value(millis(nPlusOne.totalNanos()))
                    .name("queryIndexes").beginArray();
            nPlusOne.queryIndexes().forEach(index -> json.value(index));
            json.endArray().endObject();
            return;
        }
        SlowQuery slow = (SlowQuery) finding;
        json.beginObject()
                .name("type").value("slowQuery")
                .name("sql").value(SqlNormalizer.normalize(slow.query().sql()))
                .name("callSite").value(slow.query().callSite())
                .name("durationMs").value(millis(slow.query().durationNanos()))
                .name("queryIndex").value(slow.queryIndex())
                .endObject();
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
