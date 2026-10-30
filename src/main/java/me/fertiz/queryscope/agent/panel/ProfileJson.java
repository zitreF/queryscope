package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.http.JsonWriter;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;

import java.util.List;

final class ProfileJson {

    private static final int MAX_DEPTH = 400;
    private static final int HIDDEN_BELOW_ONE_SAMPLE_IN = 1000;

    private ProfileJson() {
    }

    static String profileToJson(Profile profile, SampleStore samples, Profile.Filter filter, List<String> packages) {
        JsonWriter json = new JsonWriter().beginObject()
                .name("source").value(samples.source())
                .name("packages").beginArray();
        packages.forEach(name -> json.value(name));
        json.endArray()
                .name("intervalMs").value(samples.intervalMillis())
                .name("fromMs").value(filter.fromMillis())
                .name("toMs").value(filter.toMillis())
                .name("samples").value(profile.samples())
                .name("agentSamples").value(profile.agentSamples())
                .name("threads").beginArray();
        profile.threads().forEach(thread -> json.beginObject()
                .name("id").value(thread.id())
                .name("name").value(thread.name())
                .name("samples").value(thread.samples())
                .endObject());
        json.endArray().name("hot").beginArray();
        profile.hot().forEach(method -> json.beginObject()
                .name("method").value(method.method())
                .name("app").value(method.app())
                .name("self").value(method.self())
                .name("total").value(method.total())
                .name("selfWithLibraries").value(method.selfWithLibraries())
                .endObject());
        json.endArray().name("root");
        writeNode(json, profile.root(), Math.max(1, profile.samples() / HIDDEN_BELOW_ONE_SAMPLE_IN), 0);
        return json.endObject().toString();
    }

    private static void writeNode(JsonWriter json, Profile.Node node, int minimum, int depth) {
        json.beginObject()
                .name("method").value(node.method())
                .name("app").value(node.app())
                .name("total").value(node.total())
                .name("self").value(node.self())
                .name("children").beginArray();
        if (depth < MAX_DEPTH) {
            node.children().stream()
                    .filter(child -> child.total() >= minimum)
                    .forEach(child -> writeNode(json, child, minimum, depth + 1));
        }
        json.endArray().endObject();
    }
}
