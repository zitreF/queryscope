package me.fertiz.queryscope.agent.http;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void readsWholeNumbersApartFromOtherValues() {
        JsonNode value = Json.read("{\"id\": 7, \"big\": 12345678901, \"ratio\": 0.5, \"text\": \"7\", \"none\": null}");

        assertEquals(OptionalLong.of(7), Json.wholeNumber(value.path("id")));
        assertEquals(OptionalLong.of(12345678901L), Json.wholeNumber(value.path("big")));
        assertEquals(OptionalLong.empty(), Json.wholeNumber(value.path("ratio")));
        assertEquals(OptionalLong.empty(), Json.wholeNumber(value.path("text")));
        assertEquals(OptionalLong.empty(), Json.wholeNumber(value.path("huge")));
        assertTrue(Json.absent(value.path("none")));
        assertTrue(Json.absent(value.path("missing")));
        assertFalse(Json.absent(value.path("text")));
    }

    @Test
    void readsNothingAsNoObject() {
        assertFalse(Json.read("").isObject());
        assertFalse(Json.read("   ").isObject());
    }

    @Test
    void rejectsBrokenJsonAndTextAfterIt() {
        for (String broken : List.of("{", "{\"a\" 1}", "[1,]", "\"open", "tru", "{\"a\": 1} x", "01x", "\"\\q\"")) {
            assertThrows(IllegalArgumentException.class, () -> Json.read(broken), broken);
        }
    }

    @Test
    void refusesDeepNesting() {
        assertThrows(IllegalArgumentException.class, () -> Json.read("[".repeat(100) + "]".repeat(100)));
    }

    @Test
    void writesTreesDeeperThanJacksonAllowsByDefault() {
        ObjectNode root = Json.object();
        ObjectNode node = root;
        for (int depth = 0; depth < 400; depth++) {
            node = node.putArray("children").addObject();
        }

        assertTrue(Json.write(root).endsWith("}]}]}"));
    }

    @Test
    void escapesControlCharactersAndReadsThemBack() {
        String json = Json.write(Json.object().put("sql", "select '\"x\"'\nnext\ttab \\ \u0001"));

        assertEquals("{\"sql\":\"select '\\\"x\\\"'\\nnext\\ttab \\\\ \\u0001\"}", json);
        assertEquals("select '\"x\"'\nnext\ttab \\ \u0001", Json.read(json).path("sql").stringValue());
    }

    @Test
    void roundsToThreeDecimals() {
        assertEquals(1.235, Json.rounded(1.23456));
        assertEquals("{\"ms\":2.0}", Json.write(Json.object().put("ms", Json.rounded(2.0000004))));
    }
}
