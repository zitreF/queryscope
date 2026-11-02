package me.fertiz.queryscope.agent.console;

import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.FindingKind;
import me.fertiz.queryscope.agent.findings.NPlusOne;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.loadtest.LoadTestPlan;
import me.fertiz.queryscope.agent.loadtest.LoadTestResult;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiReportTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final String MAPPER = "com.acme.OrderMapper.toDto(OrderMapper.java:12)";
    private static final NPlusOne ORDERS = new NPlusOne("select * from orders where customer_id = ?", MAPPER,
            List.of(0, 1, 2, 3, 4), 5_000_000);

    private final SampleStore samples = new SampleStore(Clock.fixed(NOW, ZoneOffset.UTC), 100, Duration.ofMinutes(5));

    @Test
    void groupsEachFindingWithWhereItWasSeenAndAnExample() {
        List<UnitReport> reports = List.of(
                report(9, UnitKind.TASK, "TASK", "DigestJob.run", List.of(ORDERS)),
                report(8, UnitKind.HTTP, "GET", "/api/customers/2/orders", List.of(ORDERS)),
                report(7, UnitKind.HTTP, "GET", "/api/customers/1/orders", List.of(ORDERS)),
                report(6, UnitKind.HTTP, "GET", "/api/status", List.of()));

        String markdown = markdown(reports);

        assertTrue(markdown.startsWith("# QueryScope report\n\nThe application's code is in com.acme. This report "
                + "covers the 4 requests and tasks"), markdown);
        assertTrue(markdown.contains("1. N+1: 5 x select * from orders where customer_id = ? at " + MAPPER
                + "\n   - Seen in 3 units: GET /api/customers/{id}/orders (2), Task DigestJob.run (1)"
                + "\n   - Newest example: request or task 9\n"), markdown);
        assertTrue(markdown.contains("| GET /api/customers/{id}/orders | 2 | 5.0 | 10.0 ms | 20.0 ms | N+1 |"),
                markdown);
        assertTrue(markdown.contains("| GET /api/status | 1 | 5.0 | 10.0 ms | 10.0 ms | - |"), markdown);
        assertTrue(markdown.contains("No CPU samples yet."), markdown);
        assertFalse(markdown.contains("## Traced methods"), markdown);
    }

    @Test
    void listsTheHotMethodsWithTheApplicationsOwnMarked() {
        samples.add(NOW.toEpochMilli(), 1, "main", true, false,
                List.of("java.lang.Thread.run", "com.acme.Report.render", "java.lang.StringBuilder.append"));

        String markdown = markdown(List.of());

        assertTrue(markdown.contains("No findings in these requests and tasks."), markdown);
        assertTrue(markdown.contains("| `java.lang.StringBuilder.append` | 100.0% | 100.0% | no |"), markdown);
        assertTrue(markdown.contains("- 100.0% `com.acme.Report.render`"), markdown);
    }

    @Test
    void comparesTheLoadTestsNewestFirst() {
        LoadTestResult.Latency client = new LoadTestResult.Latency(185_000_000, 87_300_000, 738_000_000,
                1_440_000_000, 1_640_000_000);
        LoadTestResult naive = new LoadTestResult(1, new LoadTestPlan("GET /api/customers/naive",
                URI.create("http://127.0.0.1:8080/api/customers/naive?page=2"), 300, 10),
                LoadTestResult.State.FINISHED, NOW, 5_730_000_000L, 300, 0, null, Map.of(200, 300), client, 300,
                client, 300 * 41, 41, 41, 300 * 12_700_000L,
                List.of(new LoadTestResult.FindingCount(FindingKind.N_PLUS_ONE, "N+1: 40 x select", 300)),
                Map.of(FindingKind.N_PLUS_ONE, 300));
        LoadTestResult stopped = new LoadTestResult(2, new LoadTestPlan("GET /api/customers/{id}/card",
                URI.create("http://127.0.0.1:8080/api/customers/7/card"), 1000, 5),
                LoadTestResult.State.STOPPED, NOW, 1_000_000_000L, 40, 0, null, Map.of(200, 40), client, 40,
                client, 40 * 3 + 20, 3, 5, 0, List.of(), Map.of());

        String markdown = markdown(List.of(), List.of(stopped, naive));

        assertTrue(markdown.contains("## Load tests, newest first"), markdown);
        assertTrue(markdown.contains("| `GET /api/customers/7/card` | 40 of 1000, 5 at once, stopped | 40.0 | 87.3 ms"
                + " | 738.0 ms | 1.44 s | 3 to 5, 3.5 on average | 0.0 ms | - |\n"
                + "| `GET /api/customers/naive?page=2` | 300 of 300, 10 at once | 52.4 | 87.3 ms | 738.0 ms | 1.44 s | 41"
                + " | 12.7 ms | N+1 in 300 of 300 |"), markdown);
        assertFalse(markdown(List.of(), List.of()).contains("Load tests"));
    }

    @Test
    void listsRequestsNewestFirstWithTheirFindings() {
        List<UnitReport> reports = List.of(
                report(9, UnitKind.TASK, "TASK", "DigestJob.run", List.of(ORDERS)),
                report(8, UnitKind.HTTP, "GET", "/api/status", List.of()));

        assertEquals("#9 Task DigestJob.run · 10.0 ms · 5 queries · N+1\n"
                + "#8 GET /api/status -> 200 · 10.0 ms · 5 queries\n", AiReport.requestList(reports, 20, false));
        assertEquals("#9 Task DigestJob.run · 10.0 ms · 5 queries · N+1\n", AiReport.requestList(reports, 20, true));
        assertEquals("No requests or tasks yet.\n", AiReport.requestList(List.of(), 20, false));
    }

    private String markdown(List<UnitReport> reports) {
        return markdown(reports, List.of());
    }

    private String markdown(List<UnitReport> reports, List<LoadTestResult> loadTests) {
        Profile profile = Profile.build(samples, AppPackages.of(List.of("com.acme")), Profile.Filter.allSamples());
        return AiReport.markdown(reports, List.of("com.acme"), profile, "JDK Flight Recorder", List.of(), loadTests);
    }

    private static UnitReport report(long id, UnitKind kind, String method, String path,
                                     List<Finding> findings) {
        List<QueryExecution> queries = List.of(0, 1, 2, 3, 4).stream()
                .map(index -> new QueryExecution("select * from orders where customer_id = " + index, "",
                        index * 2_000_000L, 2_000_000, 1, MAPPER, false))
                .toList();
        return new UnitReport(new UnitTrace(id, kind, method, path, kind == UnitKind.HTTP ? 200 : 0, NOW, 10_000_000,
                queries), findings);
    }
}
