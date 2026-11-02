package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.http.Json;
import me.fertiz.queryscope.agent.methodtrace.MethodTracer;
import me.fertiz.queryscope.agent.methodtrace.TracedCall;
import me.fertiz.queryscope.agent.methodtrace.TracedMethod;
import me.fertiz.queryscope.agent.trace.UnitTracer;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static me.fertiz.queryscope.agent.panel.ReportJson.toMillis;

final class MethodTraceJson {

    private MethodTraceJson() {
    }

    static String tracesToJson(List<TracedMethod.Snapshot> traces, int limit) {
        return Json.write(Json.object()
                .put("limit", limit)
                .set("traces", Json.array(traces, MethodTraceJson::traceJson)));
    }

    static String startedToJson(MethodTracer.StartedTrace started) {
        return Json.write(Json.object()
                .put("method", started.method())
                .put("loadedClasses", started.loadedClasses()));
    }

    private static ObjectNode traceJson(TracedMethod.Snapshot trace) {
        return Json.object()
                .put("method", trace.method())
                .put("since", trace.since().toString())
                .put("calls", trace.calls())
                .put("failures", trace.failures())
                .put("totalMs", toMillis(trace.totalNanos()))
                .put("averageMs", toMillis(trace.averageNanos()))
                .put("p50Ms", toMillis(trace.p50Nanos()))
                .put("p95Ms", toMillis(trace.p95Nanos()))
                .put("p99Ms", toMillis(trace.p99Nanos()))
                .put("maxMs", toMillis(trace.maxNanos()))
                .set("slowest", Json.array(trace.slowest(), MethodTraceJson::callJson))
                .set("recent", Json.array(trace.recent(), MethodTraceJson::callJson));
    }

    private static ObjectNode callJson(TracedCall call) {
        ObjectNode json = Json.object()
                .put("endedAt", Instant.ofEpochMilli(call.endedAtMillis()).toString())
                .put("durationMs", toMillis(call.durationNanos()))
                .put("failed", call.failed())
                .put("thread", call.thread());
        if (call.unitId() == UnitTracer.NO_UNIT) {
            json.putNull("unitId");
        } else {
            json.put("unitId", call.unitId());
        }
        return json.put("unit", call.unit());
    }
}
