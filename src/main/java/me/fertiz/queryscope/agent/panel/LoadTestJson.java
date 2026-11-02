package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.findings.FindingKind;
import me.fertiz.queryscope.agent.http.JsonWriter;
import me.fertiz.queryscope.agent.loadtest.LoadTestPlan;
import me.fertiz.queryscope.agent.loadtest.LoadTestResult;
import me.fertiz.queryscope.agent.loadtest.LoadTester;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static me.fertiz.queryscope.agent.panel.ReportJson.toMillis;

final class LoadTestJson {

    private LoadTestJson() {
    }

    static String testsToJson(List<LoadTestResult> newestFirst) {
        JsonWriter json = new JsonWriter().beginObject()
                .name("maxRequests").value(LoadTestPlan.MAX_REQUESTS)
                .name("maxConcurrency").value(LoadTestPlan.MAX_CONCURRENCY)
                .name("kept").value(LoadTester.KEPT_TESTS)
                .name("tests").beginArray();
        newestFirst.forEach(result -> writeResult(json, result));
        return json.endArray().endObject().toString();
    }

    static String resultToJson(LoadTestResult result) {
        JsonWriter json = new JsonWriter();
        writeResult(json, result);
        return json.toString();
    }

    private static void writeResult(JsonWriter json, LoadTestResult result) {
        json.beginObject()
                .name("id").value(result.id())
                .name("endpoint").value(result.plan().endpoint())
                .name("target").value(result.plan().target().toString())
                .name("requests").value(result.plan().requests())
                .name("concurrency").value(result.plan().concurrency())
                .name("state").value(result.state().name().toLowerCase(Locale.ROOT))
                .name("startedAt").value(result.startedAt().toString())
                .name("elapsedMs").value(toMillis(result.elapsedNanos()))
                .name("answered").value(result.answered())
                .name("failed").value(result.failed())
                .name("firstError").value(result.firstError())
                .name("requestsPerSecond").value(result.requestsPerSecond())
                .name("statuses").beginArray();
        result.statuses().forEach((status, count) -> json.beginObject()
                .name("status").value(status)
                .name("count").value(count)
                .endObject());
        json.endArray().name("latency");
        writeLatency(json, result.client());
        json.name("server").beginObject()
                .name("tracedRequests").value(result.tracedRequests())
                .name("averageQueries").value(result.averageQueries())
                .name("fewestQueries").value(result.fewestQueries())
                .name("mostQueries").value(result.mostQueries())
                .name("averageDatabaseMs").value(toMillis(result.averageDatabaseNanos()))
                .name("latency");
        writeLatency(json, result.server());
        json.name("findings").beginArray();
        result.findings().forEach(finding -> json.beginObject()
                .name("type").value(ReportJson.typeOf(finding.kind()))
                .name("example").value(finding.example())
                .name("requests").value(finding.requests())
                .endObject());
        json.endArray().name("findingKinds").beginArray();
        result.requestsByFindingKind().entrySet().stream()
                .sorted(Map.Entry.<FindingKind, Integer>comparingByValue().reversed())
                .forEach(kind -> json.beginObject()
                        .name("type").value(ReportJson.typeOf(kind.getKey()))
                        .name("requests").value(kind.getValue())
                        .endObject());
        json.endArray().endObject().endObject();
    }

    private static void writeLatency(JsonWriter json, LoadTestResult.Latency latency) {
        json.beginObject()
                .name("averageMs").value(toMillis(latency.averageNanos()))
                .name("p50Ms").value(toMillis(latency.p50Nanos()))
                .name("p95Ms").value(toMillis(latency.p95Nanos()))
                .name("p99Ms").value(toMillis(latency.p99Nanos()))
                .name("maxMs").value(toMillis(latency.maxNanos()))
                .endObject();
    }
}
