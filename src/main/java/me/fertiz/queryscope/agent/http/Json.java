package me.fertiz.queryscope.agent.http;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.OptionalLong;
import java.util.function.Function;

public final class Json {

    private static final int MAX_READ_DEPTH = 64;
    private static final int MAX_WRITE_DEPTH = 1000;
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_READ_DEPTH).build())
                    .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(MAX_WRITE_DEPTH).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Json() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static <T> ArrayNode array(Collection<T> items, Function<T, JsonNode> toJson) {
        ArrayNode array = array();
        items.forEach(item -> array.add(toJson.apply(item)));
        return array;
    }

    public static ArrayNode strings(Collection<String> items) {
        ArrayNode array = array();
        items.forEach(array::add);
        return array;
    }

    public static ArrayNode numbers(Collection<Integer> items) {
        ArrayNode array = array();
        items.forEach(array::add);
        return array;
    }

    public static String write(JsonNode json) {
        return MAPPER.writeValueAsString(json);
    }

    public static JsonNode read(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    public static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    public static OptionalLong wholeNumber(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() ? OptionalLong.of(node.longValue()) : OptionalLong.empty();
    }

    public static double rounded(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
