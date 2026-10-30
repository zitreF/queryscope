package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.http.JsonReader;
import me.fertiz.queryscope.agent.http.LocalServer;
import me.fertiz.queryscope.agent.methodtrace.MethodTracer;
import me.fertiz.queryscope.agent.profiler.SampleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpEndpointTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final ReportStore store = new ReportStore(10);
    private final MethodTracer tracer = new MethodTracer(() -> new Class<?>[0], (className, methodName) -> () -> {
    });
    private LocalServer panel;

    @BeforeEach
    void startPanel() throws IOException {
        AppPackages acme = AppPackages.of(List.of("com.acme"));
        SampleStore samples = new SampleStore(Clock.systemUTC(), 100, Duration.ofMinutes(5));
        panel = PanelServer.start(0, true, store, acme, acme, samples, tracer);
        store.add(Reports.withFindings());
    }

    @AfterEach
    void stopPanel() {
        tracer.snapshots().forEach(trace -> tracer.stop(trace.method()));
        panel.stop();
    }

    @Test
    void introducesItselfAndListsItsTools() throws Exception {
        Map<?, ?> initialized = result(call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\"}}}"));

        assertEquals("2025-03-26", initialized.get("protocolVersion"));
        assertEquals("queryscope", ((Map<?, ?>) initialized.get("serverInfo")).get("name"));
        assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null).statusCode());
        List<?> tools = (List<?>) result(call("{\"jsonrpc\":\"2.0\",\"id\":\"two\",\"method\":\"tools/list\"}"))
                .get("tools");
        assertEquals(List.of("get_report", "list_requests", "get_request", "trace_method", "list_traces", "stop_trace"),
                tools.stream().map(tool -> ((Map<?, ?>) tool).get("name")).toList());
    }

    @Test
    void answersWithTheReportAndOneRequest() throws Exception {
        String report = toolText(callTool("get_report", "{}"));
        String requests = toolText(callTool("list_requests", "{\"onlyWithFindings\":true}"));
        String request = toolText(callTool("get_request", "{\"id\":7}"));

        assertTrue(report.startsWith("# QueryScope report"), report);
        assertTrue(report.contains("N+1: 2 x select * from orders where customer_id = ?"), report);
        assertTrue(requests.startsWith("#7 GET /customers?q=\"x\" -> 500 · 125.0 ms · 2 queries · N+1, Slow"),
                requests);
        assertTrue(request.startsWith("{\"id\":7,"), request);
    }

    @Test
    void startsListsAndStopsTraces() throws Exception {
        assertTrue(toolText(callTool("trace_method", "{\"method\":\"com.acme.OrderService#findAll\"}"))
                .startsWith("QueryScope traces com.acme.OrderService.findAll now"));
        assertTrue(toolText(callTool("list_traces", "{}")).contains("\"method\":\"com.acme.OrderService.findAll\""));
        assertEquals("QueryScope stopped tracing com.acme.OrderService.findAll.",
                toolText(callTool("stop_trace", "{\"method\":\"com.acme.OrderService.findAll\"}")));
    }

    @Test
    void reportsToolFailuresAsErrorResults() throws Exception {
        Map<?, ?> missing = result(callTool("get_request", "{\"id\":999}"));
        Map<?, ?> invalid = result(callTool("trace_method", "{\"method\":\"findAll\"}"));

        assertEquals(true, missing.get("isError"));
        assertTrue(toolText(missing).contains("keeps no request or task 999"), toolText(missing));
        assertEquals(true, invalid.get("isError"));
    }

    @Test
    void answersProtocolErrorsTheJsonRpcWay() throws Exception {
        assertEquals(-32601L, errorCode(call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\"}")));
        assertEquals(-32602L, errorCode(callTool("drop_tables", "{}")));
        HttpResponse<String> broken = post("{\"jsonrpc\":", null);
        assertEquals(400, broken.statusCode());
        assertEquals(-32700L, errorCode((Map<?, ?>) JsonReader.parse(broken.body())));
    }

    @Test
    void acceptsOnlyJsonPostsFromNoPageOrItsOwn() throws Exception {
        assertEquals(405, client.send(HttpRequest.newBuilder(mcp()).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(403, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "http://attacker.example")
                .statusCode());
        assertEquals(415, client.send(HttpRequest.newBuilder(mcp()).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode());
    }

    @Test
    void servesTheReportAsMarkdown() throws Exception {
        HttpResponse<String> report = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + panel.port() + "/api/report.md")).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, report.statusCode());
        assertEquals("text/markdown; charset=utf-8", report.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(report.body().contains("## Findings, most frequent first"), report.body());
    }

    private Map<?, ?> callTool(String name, String arguments) throws Exception {
        return call("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + arguments + "}}");
    }

    private Map<?, ?> call(String body) throws Exception {
        HttpResponse<String> response = post(body, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
        return (Map<?, ?>) JsonReader.parse(response.body());
    }

    private HttpResponse<String> post(String body, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(mcp())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI mcp() {
        return URI.create("http://localhost:" + panel.port() + "/mcp");
    }

    private static Map<?, ?> result(Map<?, ?> response) {
        return (Map<?, ?>) response.get("result");
    }

    private static String toolText(Map<?, ?> response) {
        Map<?, ?> result = response.containsKey("result") ? result(response) : response;
        return (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
    }

    private static Object errorCode(Map<?, ?> response) {
        return ((Map<?, ?>) response.get("error")).get("code");
    }
}
