package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.http.Json;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

final class ProfileJson {

    private static final int MAX_DEPTH = 400;
    private static final int HIDDEN_BELOW_ONE_SAMPLE_IN = 1000;

    private ProfileJson() {
    }

    static String profileToJson(Profile profile, SampleStore samples, Profile.Filter filter, List<String> packages) {
        return Json.write(Json.object()
                .put("source", samples.source())
                .set("packages", Json.strings(packages))
                .put("intervalMs", samples.intervalMillis())
                .put("fromMs", filter.fromMillis())
                .put("toMs", filter.toMillis())
                .put("samples", profile.samples())
                .put("agentSamples", profile.agentSamples())
                .set("threads", Json.array(profile.threads(), thread -> Json.object()
                        .put("id", thread.id())
                        .put("name", thread.name())
                        .put("samples", thread.samples())))
                .set("hot", Json.array(profile.hot(), method -> Json.object()
                        .put("method", method.method())
                        .put("app", method.app())
                        .put("self", method.self())
                        .put("total", method.total())
                        .put("selfWithLibraries", method.selfWithLibraries())))
                .set("root", nodeJson(profile.root(), Math.max(1, profile.samples() / HIDDEN_BELOW_ONE_SAMPLE_IN), 0)));
    }

    private static ObjectNode nodeJson(Profile.Node node, int minimum, int depth) {
        ArrayNode children = Json.array();
        if (depth < MAX_DEPTH) {
            node.children().stream()
                    .filter(child -> child.total() >= minimum)
                    .forEach(child -> children.add(nodeJson(child, minimum, depth + 1)));
        }
        return Json.object()
                .put("method", node.method())
                .put("app", node.app())
                .put("total", node.total())
                .put("self", node.self())
                .set("children", children);
    }
}
