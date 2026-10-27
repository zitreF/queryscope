package me.fertiz.queryscope.agent.panel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportJsonTest {

    @Test
    void writesRequestQueriesAndFindings() {
        String expected = "{\"id\":7,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/customers?q=\\\"x\\\"\",\"status\":500,"
                + "\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":125.000,\"queries\":["
                + "{\"sql\":\"select * from orders where customer_id = 1\",\"offsetMs\":1.000,\"durationMs\":0.500,"
                + "\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"failed\":false},"
                + "{\"sql\":\"select * from orders where customer_id = 2\",\"offsetMs\":2.000,\"durationMs\":120.000,"
                + "\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"failed\":true}],\"findings\":["
                + "{\"type\":\"nPlusOne\",\"sql\":\"select * from orders where customer_id = ?\","
                + "\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"executions\":2,\"totalMs\":120.500,"
                + "\"queryIndexes\":[0,1]},"
                + "{\"type\":\"slowQuery\",\"sql\":\"select * from orders where customer_id = ?\","
                + "\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"durationMs\":120.000,\"queryIndex\":1}]}";

        assertEquals(expected, ReportJson.of(Reports.withFindings()));
    }

    @Test
    void writesListsAndNullCallSites() {
        String json = ReportJson.of(List.of(Reports.plain(2, "/b"), Reports.plain(1, "/a")));

        assertEquals("[{\"id\":2,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/b\",\"status\":200,"
                + "\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":2.000,\"queries\":[{\"sql\":\"select 1\","
                + "\"offsetMs\":0.500,\"durationMs\":0.250,\"callSite\":null,\"failed\":false}],\"findings\":[]},"
                + "{\"id\":1,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/a\",\"status\":200,"
                + "\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":2.000,\"queries\":[{\"sql\":\"select 1\","
                + "\"offsetMs\":0.500,\"durationMs\":0.250,\"callSite\":null,\"failed\":false}],\"findings\":[]}]", json);
    }

    @Test
    void escapesControlCharacters() {
        String json = new JsonWriter().value("line\nnext\ttab \\ \u0001").toString();

        assertEquals("\"line\\nnext\\ttab \\\\ \\u0001\"", json);
    }
}
