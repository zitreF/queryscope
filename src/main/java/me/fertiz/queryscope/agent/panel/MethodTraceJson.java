package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.http.JsonWriter;
import me.fertiz.queryscope.agent.methodtrace.MethodTracer;
import me.fertiz.queryscope.agent.methodtrace.TracedCall;
import me.fertiz.queryscope.agent.methodtrace.TracedMethod;
import me.fertiz.queryscope.agent.trace.UnitTracer;

import java.time.Instant;
import java.util.List;

import static me.fertiz.queryscope.agent.panel.ReportJson.toMillis;

final class MethodTraceJson {

    private MethodTraceJson() {
    }

    static String tracesToJson(List<TracedMethod.Snapshot> traces, int limit) {
        JsonWriter json = new JsonWriter().beginObject()
                .name("limit").value(limit)
                .name("traces").beginArray();
        traces.forEach(trace -> writeTrace(json, trace));
        return json.endArray().endObject().toString();
    }

    static String startedToJson(MethodTracer.StartedTrace started) {
        return new JsonWriter().beginObject()
                .name("method").value(started.method())
                .name("loadedClasses").value(started.loadedClasses())
                .endObject().toString();
    }

    private static void writeTrace(JsonWriter json, TracedMethod.Snapshot trace) {
        json.beginObject()
                .name("method").value(trace.method())
                .name("since").value(trace.since().toString())
                .name("calls").value(trace.calls())
                .name("failures").value(trace.failures())
                .name("totalMs").value(toMillis(trace.totalNanos()))
                .name("averageMs").value(toMillis(trace.averageNanos()))
                .name("p50Ms").value(toMillis(trace.p50Nanos()))
                .name("p95Ms").value(toMillis(trace.p95Nanos()))
                .name("p99Ms").value(toMillis(trace.p99Nanos()))
                .name("maxMs").value(toMillis(trace.maxNanos()))
                .name("slowest").beginArray();
        trace.slowest().forEach(call -> writeCall(json, call));
        json.endArray().name("recent").beginArray();
        trace.recent().forEach(call -> writeCall(json, call));
        json.endArray().endObject();
    }

    private static void writeCall(JsonWriter json, TracedCall call) {
        json.beginObject()
                .name("endedAt").value(Instant.ofEpochMilli(call.endedAtMillis()).toString())
                .name("durationMs").value(toMillis(call.durationNanos()))
                .name("failed").value(call.failed())
                .name("thread").value(call.thread())
                .name("unitId");
        if (call.unitId() == UnitTracer.NO_UNIT) {
            json.nullValue();
        } else {
            json.value(call.unitId());
        }
        json.name("unit").value(call.unit()).endObject();
    }
}
