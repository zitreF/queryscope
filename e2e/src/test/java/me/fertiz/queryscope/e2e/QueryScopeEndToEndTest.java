package me.fertiz.queryscope.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryScopeEndToEndTest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration REPORT_TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> ENDPOINTS =
            List.of("/api/customers/naive", "/api/customers/optimized", "/api/reports/customer-activity");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Process demoApp;
    private static Path log;
    private static int appPort;
    private static int panelPort;

    @BeforeAll
    static void startDemoAppWithAgent() throws Exception {
        appPort = freePort();
        panelPort = freePort();
        log = Path.of("build", "e2e-demo-app.log");
        Files.createDirectories(log.getParent());
        demoApp = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-javaagent:" + System.getProperty("queryscope.agentJar") + "=port=" + panelPort,
                "-jar", System.getProperty("queryscope.demoJar"),
                "--server.port=" + appPort)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        waitUntilAppResponds();
        for (String endpoint : ENDPOINTS) {
            get(appPort, endpoint);
        }
    }

    @AfterAll
    static void stopDemoApp() throws InterruptedException {
        demoApp.destroy();
        if (!demoApp.waitFor(10, TimeUnit.SECONDS)) {
            demoApp.destroyForcibly();
        }
    }

    @BeforeEach
    void clearPanel() throws Exception {
        HttpRequest clear = HttpRequest.newBuilder(panel("/api/requests")).DELETE().build();
        assertEquals(204, CLIENT.send(clear, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void agentInstrumentsTheAppWithoutErrors() throws IOException {
        String output = Files.readString(log);

        assertTrue(output.contains("QueryScope panel is running at http://localhost:" + panelPort), output);
        assertFalse(output.contains("Could not instrument"), output);
    }

    @Test
    void reportsNPlusOneOnTheLineThatTriggersIt() throws Exception {
        JsonNode report = requestAndReport("/api/customers/naive");

        assertEquals(41, report.path("queries").size());
        JsonNode finding = single(report.path("findings"));
        assertEquals("nPlusOne", finding.path("type").asString());
        assertEquals(40, finding.path("executions").asInt());
        assertTrue(finding.path("sql").asString().contains("from orders"), finding.toString());
        assertTrue(finding.path("callSite").asString()
                .startsWith("me.fertiz.queryscope.demo.customer.CustomerSummary.of("), finding.toString());
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

    private static JsonNode requestAndReport(String path) throws Exception {
        assertEquals(200, get(appPort, path).statusCode());
        Instant deadline = Instant.now().plus(REPORT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Optional<JsonNode> report = storedReport(path);
            if (report.isPresent()) {
                return report.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The panel never reported " + path);
    }

    private static Optional<JsonNode> storedReport(String path) throws Exception {
        JsonNode reports = JSON.readTree(get(panelPort, "/api/requests").body());
        for (int index = 0; index < reports.size(); index++) {
            if (reports.get(index).path("path").asString().equals(path)) {
                return Optional.of(reports.get(index));
            }
        }
        return Optional.empty();
    }

    private static JsonNode single(JsonNode array) {
        assertEquals(1, array.size(), array.toString());
        return array.get(0);
    }

    private static void waitUntilAppResponds() throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!demoApp.isAlive()) {
                throw new AssertionError("Demo app exited during startup:\n" + logTail());
            }
            try {
                get(appPort, "/api/customers/optimized");
                return;
            } catch (IOException notReadyYet) {
                Thread.sleep(500);
            }
        }
        throw new AssertionError("Demo app did not start within " + STARTUP_TIMEOUT + ":\n" + logTail());
    }

    private static String logTail() throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(log));
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI panel(String path) {
        return URI.create("http://localhost:" + panelPort + path);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
