package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.findings.FindingKind;
import me.fertiz.queryscope.agent.http.Json;
import me.fertiz.queryscope.agent.loadtest.LoadTestPlan;
import me.fertiz.queryscope.agent.loadtest.LoadTestResult;
import me.fertiz.queryscope.agent.loadtest.LoadTester;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static me.fertiz.queryscope.agent.panel.ReportJson.toMillis;

final class LoadTestJson {

    private LoadTestJson() {
    }

    static String testsToJson(List<LoadTestResult> newestFirst) {
        return Json.write(Json.object()
                .put("maxRequests", LoadTestPlan.MAX_REQUESTS)
                .put("maxConcurrency", LoadTestPlan.MAX_CONCURRENCY)
                .put("kept", LoadTester.KEPT_TESTS)
                .set("tests", Json.array(newestFirst, LoadTestJson::resultJson)));
    }

    static String resultToJson(LoadTestResult result) {
        return Json.write(resultJson(result));
    }

    private static ObjectNode resultJson(LoadTestResult result) {
        ArrayNode statuses = Json.array();
        result.statuses().forEach((status, count) -> statuses.add(Json.object()
                .put("status", status)
                .put("count", count)));
        return Json.object()
                .put("id", result.id())
                .put("endpoint", result.plan().endpoint())
                .put("method", result.plan().method())
                .put("target", result.plan().target().toString())
                .set("headerNames", Json.strings(result.plan().headers().keySet()))
                .put("bodyBytes", result.plan().bodyBytes().length)
                .put("requests", result.plan().requests())
                .put("concurrency", result.plan().concurrency())
                .put("state", result.state().name().toLowerCase(Locale.ROOT))
                .put("startedAt", result.startedAt().toString())
                .put("elapsedMs", toMillis(result.elapsedNanos()))
                .put("answered", result.answered())
                .put("failed", result.failed())
                .put("firstError", result.firstError())
                .put("requestsPerSecond", Json.rounded(result.requestsPerSecond()))
                .set("statuses", statuses)
                .set("latency", latencyJson(result.client()))
                .set("server", serverJson(result));
    }

    private static ObjectNode serverJson(LoadTestResult result) {
        List<Map.Entry<FindingKind, Integer>> kinds = result.requestsByFindingKind().entrySet().stream()
                .sorted(Map.Entry.<FindingKind, Integer>comparingByValue().reversed())
                .toList();
        return Json.object()
                .put("tracedRequests", result.tracedRequests())
                .put("averageQueries", Json.rounded(result.averageQueries()))
                .put("fewestQueries", result.fewestQueries())
                .put("mostQueries", result.mostQueries())
                .put("averageDatabaseMs", toMillis(result.averageDatabaseNanos()))
                .set("latency", latencyJson(result.server()))
                .set("findings", Json.array(result.findings(), finding -> Json.object()
                        .put("type", ReportJson.typeOf(finding.kind()))
                        .put("example", finding.example())
                        .put("requests", finding.requests())))
                .set("findingKinds", Json.array(kinds, kind -> Json.object()
                        .put("type", ReportJson.typeOf(kind.getKey()))
                        .put("requests", kind.getValue())));
    }

    private static ObjectNode latencyJson(LoadTestResult.Latency latency) {
        return Json.object()
                .put("averageMs", toMillis(latency.averageNanos()))
                .put("p50Ms", toMillis(latency.p50Nanos()))
                .put("p95Ms", toMillis(latency.p95Nanos()))
                .put("p99Ms", toMillis(latency.p99Nanos()))
                .put("maxMs", toMillis(latency.maxNanos()));
    }
}
