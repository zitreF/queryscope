package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.calltree.CallNode;
import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.findings.DuplicateQuery;
import me.fertiz.queryscope.agent.findings.LargeResult;
import me.fertiz.queryscope.agent.findings.RepeatedWrite;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.findings.SlowMethod;
import me.fertiz.queryscope.agent.findings.TooManyQueries;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportJsonTest {

    @Test
    void writesRequestQueriesAndFindings() {
        String expected = "{\"id\":7,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/customers?q=\\\"x\\\"\",\"status\":500,"
                + "\"port\":0,\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":125.0,\"queries\":["
                + "{\"sql\":\"select * from orders where customer_id = 1\",\"parameters\":\"\",\"offsetMs\":1.0,"
                + "\"durationMs\":0.5,\"rows\":3,\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"failed\":false},"
                + "{\"sql\":\"select * from orders where customer_id = 2\",\"parameters\":\"\",\"offsetMs\":2.0,"
                + "\"durationMs\":120.0,\"rows\":null,\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"failed\":true}],"
                + "\"omittedQueries\":0,\"omittedMs\":0.0,\"findings\":["
                + "{\"type\":\"nPlusOne\",\"sql\":\"select * from orders where customer_id = ?\","
                + "\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"executions\":2,\"totalMs\":120.5,"
                + "\"queryIndexes\":[0,1]},"
                + "{\"type\":\"slowQuery\",\"sql\":\"select * from orders where customer_id = ?\","
                + "\"callSite\":\"com.acme.Mapper.toDto(Mapper.java:7)\",\"durationMs\":120.0,\"queryIndex\":1}],"
                + "\"methodCount\":0}";

        assertEquals(expected, ReportJson.reportToJson(Reports.withFindings()));
    }

    @Test
    void writesListsAndNullCallSites() {
        String json = ReportJson.reportsToJson(List.of(Reports.plain(2, "/b"), Reports.plain(1, "/a")));

        assertEquals("[{\"id\":2,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/b\",\"status\":200,\"port\":0,"
                + "\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":2.0,\"queries\":[{\"sql\":\"select 1\","
                + "\"parameters\":\"\",\"offsetMs\":0.5,\"durationMs\":0.25,\"rows\":1,\"callSite\":null,\"failed\":false}],"
                + "\"omittedQueries\":0,\"omittedMs\":0.0,\"findings\":[],\"methodCount\":0},"
                + "{\"id\":1,\"kind\":\"http\",\"method\":\"GET\",\"path\":\"/a\",\"status\":200,\"port\":0,"
                + "\"startedAt\":\"2026-10-25T20:00:00Z\",\"durationMs\":2.0,\"queries\":[{\"sql\":\"select 1\","
                + "\"parameters\":\"\",\"offsetMs\":0.5,\"durationMs\":0.25,\"rows\":1,\"callSite\":null,\"failed\":false}],"
                + "\"omittedQueries\":0,\"omittedMs\":0.0,\"findings\":[],\"methodCount\":0}]", json);
    }

    @Test
    void writesEveryKindOfFinding() {
        QueryExecution query = new QueryExecution("select * from events", "[1]", 0, 1_000_000, 900, null, false);
        UnitTrace trace = new UnitTrace(9, UnitKind.TASK, "TASK", "ImportJob.run", 0, Reports.STARTED_AT,
                2_000_000, List.of(query));

        String json = ReportJson.reportToJson(new UnitReport(trace, List.of(
                new RepeatedWrite("insert into events values (?)", null, List.of(0), 1_000_000),
                new DuplicateQuery("select * from events where id = ?", "[1]", null, List.of(0, 0)),
                new LargeResult(0, "select * from events", query),
                new TooManyQueries(31, 30, 1_000_000, 2_000_000),
                new SlowMethod("com.acme.ImportJob.parse", 4, 1_500_000, 2_000_000))));

        assertTrue(json.contains("\"kind\":\"task\""), json);
        assertTrue(json.contains("{\"type\":\"repeatedWrite\",\"sql\":\"insert into events values (?)\","
                + "\"callSite\":null,\"executions\":1,\"totalMs\":1.0,\"queryIndexes\":[0]}"), json);
        assertTrue(json.contains("{\"type\":\"duplicateQuery\",\"sql\":\"select * from events where id = ?\","
                + "\"parameters\":\"[1]\",\"callSite\":null,\"executions\":2,\"queryIndexes\":[0,0]}"), json);
        assertTrue(json.contains("{\"type\":\"largeResult\",\"sql\":\"select * from events\",\"callSite\":null,"
                + "\"rows\":900,\"queryIndex\":0}"), json);
        assertTrue(json.contains("{\"type\":\"tooManyQueries\",\"queries\":31,\"limit\":30,\"databaseMs\":1.0,"
                + "\"durationMs\":2.0}"), json);
        assertTrue(json.contains("{\"type\":\"slowMethod\",\"method\":\"com.acme.ImportJob.parse\",\"calls\":4,"
                + "\"ownMs\":1.5,\"durationMs\":2.0}"), json);
    }

    @Test
    void writesHowManyQueriesWereLeftOut() {
        UnitTrace trace = new UnitTrace(5, UnitKind.TASK, "TASK", "ImportJob.run", 0, Reports.STARTED_AT,
                9_000_000, List.of(), CallTree.EMPTY, 0, 1, 500, 2_500_000, 0, 0);

        String json = ReportJson.reportToJson(new UnitReport(trace, List.of()));

        assertTrue(json.contains("\"queries\":[],\"omittedQueries\":500,\"omittedMs\":2.5,"), json);
    }

    @Test
    void writesTheCallTreeWithItsQueries() {
        CallNode toDto = new CallNode("com.acme.Mapper.toDto", 2, 600_000, 600_000, false, List.of(0, 1), List.of());
        CallNode list = new CallNode("com.acme.Service.list", 1, 1_000_000, 400_000, true, List.of(), List.of(toDto));
        UnitTrace trace = new UnitTrace(4, UnitKind.HTTP, "GET", "/orders", 200, Reports.STARTED_AT, 2_000_000,
                List.of(), new CallTree(List.of(list), List.of(2), false), 3, 1, 0, 0, 0, 0);

        String json = ReportJson.callTreeToJson(new UnitReport(trace, List.of()), List.of("com.acme"));

        assertEquals("{\"id\":4,\"durationMs\":2.0,\"packages\":[\"com.acme\"],\"truncated\":false,"
                + "\"classesLoaded\":3,\"warmUp\":false,\"outsideQueryIndexes\":[2],\"methods\":["
                + "{\"method\":\"com.acme.Service.list\",\"calls\":1,\"totalMs\":1.0,\"selfMs\":0.4,"
                + "\"startedBeforeUnit\":true,\"queryIndexes\":[],\"children\":["
                + "{\"method\":\"com.acme.Mapper.toDto\",\"calls\":2,\"totalMs\":0.6,\"selfMs\":0.6,"
                + "\"startedBeforeUnit\":false,\"queryIndexes\":[0,1],\"children\":[]}]}]}", json);
    }
}
