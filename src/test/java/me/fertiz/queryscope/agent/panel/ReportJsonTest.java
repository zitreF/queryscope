package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.data.CallNode;
import me.fertiz.queryscope.agent.data.CallTree;
import me.fertiz.queryscope.agent.data.DuplicateQuery;
import me.fertiz.queryscope.agent.data.LargeResult;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RepeatedWrite;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.SlowMethod;
import me.fertiz.queryscope.agent.data.TooManyQueries;
import me.fertiz.queryscope.agent.data.UnitKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportJsonTest {

    @Test
    void writesRequestQueriesAndFindings() {
        String expected = "{\"id\":7,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/customers?q=\\\"x\\\"\",\"status\":500,"
                + "\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":125.000,\"queries\":["
                + "{\"sql\":\"select * from orders where customer_id = 1\",\"parameters\":\"\",\"offsetMs\":1.000,"
                + "\"durationMs\":0.500,\"rows\":3,\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"failed\":false},"
                + "{\"sql\":\"select * from orders where customer_id = 2\",\"parameters\":\"\",\"offsetMs\":2.000,"
                + "\"durationMs\":120.000,\"rows\":null,\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"failed\":true}],\"findings\":["
                + "{\"type\":\"nPlusOne\",\"sql\":\"select * from orders where customer_id = ?\","
                + "\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"executions\":2,\"totalMs\":120.500,"
                + "\"queryIndexes\":[0,1]},"
                + "{\"type\":\"slowQuery\",\"sql\":\"select * from orders where customer_id = ?\","
                + "\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"durationMs\":120.000,\"queryIndex\":1}],"
                + "\"methods\":0}";

        assertEquals(expected, ReportJson.of(Reports.withFindings()));
    }

    @Test
    void writesListsAndNullCallSites() {
        String json = ReportJson.of(List.of(Reports.plain(2, "/b"), Reports.plain(1, "/a")));

        assertEquals("[{\"id\":2,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/b\",\"status\":200,"
                + "\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":2.000,\"queries\":[{\"sql\":\"select 1\","
                + "\"parameters\":\"\",\"offsetMs\":0.500,\"durationMs\":0.250,\"rows\":1,\"callSite\":null,\"failed\":false}],"
                + "\"findings\":[],\"methods\":0},"
                + "{\"id\":1,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/a\",\"status\":200,"
                + "\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":2.000,\"queries\":[{\"sql\":\"select 1\","
                + "\"parameters\":\"\",\"offsetMs\":0.500,\"durationMs\":0.250,\"rows\":1,\"callSite\":null,\"failed\":false}],"
                + "\"findings\":[],\"methods\":0}]", json);
    }

    @Test
    void writesEveryKindOfFinding() {
        QueryExecution query = new QueryExecution("select * from events", "[1]", 0, 1_000_000, 900, null, false);
        RequestTrace trace = new RequestTrace(9, UnitKind.TASK, "TASK", "ImportJob.run", 0, Reports.STARTED_AT,
                2_000_000, List.of(query));

        String json = ReportJson.of(new RequestReport(trace, List.of(
                new RepeatedWrite("insert into events values (?)", null, List.of(0), 1_000_000),
                new DuplicateQuery("select * from events where id = ?", "[1]", null, List.of(0, 0)),
                new LargeResult(0, query),
                new TooManyQueries(31, 30, 1_000_000, 2_000_000),
                new SlowMethod("com.acme.ImportJob.parse", 4, 1_500_000, 2_000_000))));

        assertTrue(json.contains("\"kind\":\"task\""), json);
        assertTrue(json.contains("{\"type\":\"repeatedWrite\",\"sql\":\"insert into events values (?)\","
                + "\"callSite\":null,\"executions\":1,\"totalMs\":1.000,\"queryIndexes\":[0]}"), json);
        assertTrue(json.contains("{\"type\":\"duplicateQuery\",\"sql\":\"select * from events where id = ?\","
                + "\"parameters\":\"[1]\",\"callSite\":null,\"executions\":2,\"queryIndexes\":[0,0]}"), json);
        assertTrue(json.contains("{\"type\":\"largeResult\",\"sql\":\"select * from events\",\"callSite\":null,"
                + "\"rows\":900,\"queryIndex\":0}"), json);
        assertTrue(json.contains("{\"type\":\"tooManyQueries\",\"queries\":31,\"limit\":30,\"databaseMs\":1.000,"
                + "\"durationMs\":2.000}"), json);
        assertTrue(json.contains("{\"type\":\"slowMethod\",\"method\":\"com.acme.ImportJob.parse\",\"calls\":4,"
                + "\"ownMs\":1.500,\"durationMs\":2.000}"), json);
    }

    @Test
    void writesTheCallTreeWithItsQueries() {
        CallNode toDto = new CallNode("com.acme.Mapper.toDto", 2, 600_000, 600_000, false, List.of(0, 1), List.of());
        CallNode list = new CallNode("com.acme.Service.list", 1, 1_000_000, 400_000, true, List.of(), List.of(toDto));
        RequestTrace trace = new RequestTrace(4, UnitKind.HTTP, "GET", "/orders", 200, Reports.STARTED_AT, 2_000_000,
                List.of(), new CallTree(List.of(list), List.of(2), false), 3, 1);

        String json = ReportJson.ofCalls(new RequestReport(trace, List.of()), List.of("com.acme"));

        assertEquals("{\"id\":4,\"durationMs\":2.000,\"packages\":[\"com.acme\"],\"truncated\":false,"
                + "\"classesLoaded\":3,\"warmUp\":false,\"outsideQueryIndexes\":[2],\"methods\":["
                + "{\"method\":\"com.acme.Service.list\",\"calls\":1,\"totalMs\":1.000,\"selfMs\":0.400,"
                + "\"startedBeforeUnit\":true,\"queryIndexes\":[],\"children\":["
                + "{\"method\":\"com.acme.Mapper.toDto\",\"calls\":2,\"totalMs\":0.600,\"selfMs\":0.600,"
                + "\"startedBeforeUnit\":false,\"queryIndexes\":[0,1],\"children\":[]}]}]}", json);
    }

    @Test
    void escapesControlCharacters() {
        String json = new JsonWriter().value("line\nnext\ttab \\ \u0001").toString();

        assertEquals("\"line\\nnext\\ttab \\\\ \\u0001\"", json);
    }
}
