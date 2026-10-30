package me.fertiz.queryscope.agent.http;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonReaderTest {

    @Test
    void readsObjectsArraysAndEveryKindOfValue() {
        Object value = JsonReader.parse(
                " {\"id\": 7, \"name\": \"tools/call\", \"ratio\": 0.5, \"on\": true, \"off\": false, \"none\": null,"
                        + " \"list\": [1, \"two\", {}], \"empty\": []} ");

        assertEquals(Map.of("id", 7L, "name", "tools/call", "ratio", 0.5, "on", true, "off", false,
                "list", List.of(1L, "two", Map.of()), "empty", List.of()), withoutNulls(value));
        assertEquals(true, ((Map<?, ?>) value).containsKey("none"));
    }

    @Test
    void decodesEscapes() {
        assertEquals("a\"b\\c/d\n\t\u00e9\u2028", JsonReader.parse("\"a\\\"b\\\\c\\/d\\n\\t\\u00e9\\u2028\""));
    }

    @Test
    void writesWhatItReadsBack() {
        String text = new JsonWriter().beginObject().name("sql").value("select '\"x\"'\n").endObject().toString();

        assertEquals(Map.of("sql", "select '\"x\"'\n"), JsonReader.parse(text));
    }

    @Test
    void rejectsBrokenJson() {
        for (String broken : List.of("", "{", "{\"a\" 1}", "[1,]", "\"open", "tru", "{\"a\": 1} x", "01x", "\"\\q\"")) {
            assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(broken), broken);
        }
    }

    @Test
    void refusesDeepNesting() {
        char[] brackets = new char[100];
        Arrays.fill(brackets, '[');

        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(new String(brackets)));
    }

    private static Object withoutNulls(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> {
                if (entry != null) {
                    copy.put(key, withoutNulls(entry));
                }
            });
            return copy;
        }
        return value;
    }
}
