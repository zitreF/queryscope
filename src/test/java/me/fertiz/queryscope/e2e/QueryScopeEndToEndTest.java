package me.fertiz.queryscope.e2e;

import me.fertiz.queryscope.demo.DemoApplication;
import me.fertiz.queryscope.demo.customer.CustomerController;
import me.fertiz.queryscope.demo.customer.CustomerService;
import me.fertiz.queryscope.demo.customer.CustomerSummary;
import me.fertiz.queryscope.demo.report.RevenueSheetService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static me.fertiz.queryscope.e2e.DemoProcess.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryScopeEndToEndTest {

    private static final Duration REPORT_TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> ENDPOINTS =
            List.of("/api/customers/naive", "/api/customers/optimized", "/api/reports/customer-activity");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static DemoProcess demo;
    private static int appPort;
    private static int panelPort;

    @BeforeAll
    static void startDemoAppWithAgent() throws Exception {
        appPort = DemoProcess.freePort();
        panelPort = DemoProcess.freePort();
        demo = DemoProcess.start(List.of(
                        DemoProcess.JAVA,
                        "-javaagent:" + DemoProcess.AGENT_JAR + "=port=" + panelPort,
                        "-cp", DemoProcess.DEMO_CLASSPATH,
                        DemoApplication.class.getName(),
                        "--server.port=" + appPort),
                Path.of("build", "e2e-demo-app.log"), appPort);
        for (String endpoint : ENDPOINTS) {
            get(appPort, endpoint);
        }
    }

    @AfterAll
    static void stopDemoApp() throws InterruptedException {
        demo.close();
    }

    @BeforeEach
    void clearPanel() throws Exception {
        HttpRequest clear = HttpRequest.newBuilder(panel("/api/requests")).DELETE().build();
        assertEquals(204, CLIENT.send(clear, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void agentInstrumentsTheAppWithoutErrors() throws IOException {
        String output = demo.output();

        assertTrue(output.contains("QueryScope panel is running at http://localhost:" + panelPort), output);
        assertTrue(output.contains("QueryScope records the call tree of me.fertiz.queryscope.demo"), output);
        assertFalse(output.contains("Could not instrument"), output);
    }

    @Test
    void buildsTheCallTreeDownToTheMethodBehindTheNPlusOne() throws Exception {
        JsonNode report = requestAndReport("/api/customers/naive");
        JsonNode calls = JSON.readTree(get(panelPort, "/api/requests/" + report.path("id").asLong() + "/calls").body());

        assertEquals("me.fertiz.queryscope.demo", single(calls.path("packages")).asString());
        JsonNode controller = method(calls.path("methods"), CustomerController.class.getName() + ".naive");
        JsonNode service = method(controller.path("children"), CustomerService.class.getName() + ".summariesNaive");
        JsonNode summary = method(service.path("children"), CustomerSummary.class.getName() + ".of");
        assertEquals(1, service.path("queryIndexes").size());
        assertEquals(40, summary.path("calls").asInt());
        assertEquals(40, summary.path("queryIndexes").size());
        assertTrue(controller.path("totalMs").asDouble() >= summary.path("totalMs").asDouble(), controller.toString());
    }

    @Test
    void keepsRequestsThatRanOnlyJavaCodeForTheCallTree() throws Exception {
        JsonNode report = requestAndReport("/api/status");

        assertEquals(0, report.path("queries").size());
        assertEquals(1, report.path("methods").asInt());
    }

    @Test
    void pointsAtTheMethodThatSpendsItsTimeOutsideSql() throws Exception {
        JsonNode report = requestAndReport("/api/reports/daily-revenue");

        JsonNode slow = finding(report, "slowMethod");
        assertEquals(RevenueSheetService.class.getName() + ".dailyRevenue", slow.path("method").asString());
        assertEquals(1, slow.path("calls").asInt());
        assertEquals(1, report.path("queries").size());
    }

    @Test
    void reportsNPlusOneOnTheLineThatTriggersIt() throws Exception {
        JsonNode report = requestAndReport("/api/customers/naive");

        assertEquals(41, report.path("queries").size());
        JsonNode finding = finding(report, "nPlusOne");
        assertEquals(40, finding.path("executions").asInt());
        assertTrue(finding.path("sql").asString().contains("from orders"), finding.toString());
        assertTrue(finding.path("callSite").asString()
                .startsWith("me.fertiz.queryscope.demo.customer.CustomerSummary.of("), finding.toString());
        assertEquals(41, finding(report, "tooManyQueries").path("queries").asInt());
    }

    @Test
    void reportsTheSameLookupRunTwiceWithTheSameParameters() throws Exception {
        JsonNode report = requestAndReport("/api/customers/7/card");

        JsonNode finding = single(report.path("findings"));
        assertEquals("duplicateQuery", finding.path("type").asString());
        assertEquals("[7]", finding.path("parameters").asString());
        assertEquals(2, finding.path("executions").asInt());
        assertTrue(finding.path("callSite").asString()
                .startsWith("me.fertiz.queryscope.demo.customer.CustomerCardService.nameOf("), finding.toString());
    }

    @Test
    void reportsInsertsThatWereNotBatched() throws Exception {
        String path = "/api/reports/visits?count=12";
        assertEquals(200, DemoProcess.post(appPort, path).statusCode());

        JsonNode finding = finding(reportFor(panelPort, path), "repeatedWrite");
        assertEquals(12, finding.path("executions").asInt());
        assertTrue(finding.path("sql").asString().startsWith("insert into audit_events"), finding.toString());
    }

    @Test
    void reportsQueriesThatLoadFarMoreRowsThanTheyShow() throws Exception {
        JsonNode report = requestAndReport("/api/reports/recent-returns");

        JsonNode finding = finding(report, "largeResult");
        assertEquals(50_000, finding.path("rows").asInt());
        assertEquals(50_000, report.path("queries").get(0).path("rows").asInt());
    }

    @Test
    void fetchJoinRunsOneQueryWithoutFindings() throws Exception {
        JsonNode report = requestAndReport("/api/customers/optimized");

        assertEquals(1, report.path("queries").size());
        assertTrue(report.path("findings").isEmpty(), report.path("findings").toString());
    }

    @Test
    void flagsTheUnindexedJoinAsSlow() throws Exception {
        JsonNode report = requestAndReport("/api/reports/customer-activity");

        JsonNode finding = single(report.path("findings"));
        assertEquals("slowQuery", finding.path("type").asString());
        assertTrue(finding.path("durationMs").asDouble() >= 100, finding.toString());
        assertTrue(finding.path("callSite").asString()
                .startsWith("me.fertiz.queryscope.demo.report.AuditReportService.mostActiveCustomers("),
                finding.toString());
    }

    @Test
    void panelStreamsFinishedRequestsLive() throws Exception {
        HttpResponse<Stream<String>> events = CLIENT.send(HttpRequest.newBuilder(panel("/api/events")).build(),
                HttpResponse.BodyHandlers.ofLines());
        Iterator<String> lines = events.body().iterator();

        assertTimeoutPreemptively(REPORT_TIMEOUT, () -> {
            assertEquals(": connected", lines.next());
            get(appPort, "/api/customers/optimized");
            String event = lines.next();
            while (!event.equals("event: request")) {
                event = lines.next();
            }
            JsonNode report = JSON.readTree(lines.next().substring("data: ".length()));
            assertEquals("/api/customers/optimized", report.path("path").asString());
        });
    }

    @Test
    void servesThePanelPage() throws Exception {
        HttpResponse<String> page = get(panelPort, "/");

        assertTrue(page.body().contains("<title>QueryScope</title>"));
    }

    static JsonNode reportFor(int panelPort, String path) throws Exception {
        Instant deadline = Instant.now().plus(REPORT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Optional<JsonNode> report = storedReport(panelPort, path);
            if (report.isPresent()) {
                return report.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The panel never reported " + path);
    }

    private static JsonNode requestAndReport(String path) throws Exception {
        assertEquals(200, get(appPort, path).statusCode());
        return reportFor(panelPort, path);
    }

    private static Optional<JsonNode> storedReport(int panelPort, String path) throws Exception {
        JsonNode reports = JSON.readTree(get(panelPort, "/api/requests").body());
        for (int index = 0; index < reports.size(); index++) {
            if (reports.get(index).path("path").asString().equals(path)) {
                return Optional.of(reports.get(index));
            }
        }
        return Optional.empty();
    }

    private static JsonNode finding(JsonNode report, String type) {
        JsonNode findings = report.path("findings");
        for (int index = 0; index < findings.size(); index++) {
            if (findings.get(index).path("type").asString().equals(type)) {
                return findings.get(index);
            }
        }
        throw new AssertionError("No " + type + " finding in " + findings);
    }

    private static JsonNode method(JsonNode nodes, String name) {
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).path("method").asString().equals(name)) {
                return nodes.get(index);
            }
        }
        throw new AssertionError("No call of " + name + " in " + nodes);
    }

    private static JsonNode single(JsonNode array) {
        assertEquals(1, array.size(), array.toString());
        return array.get(0);
    }

    private static URI panel(String path) {
        return URI.create("http://localhost:" + panelPort + path);
    }
}
