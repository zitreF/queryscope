package me.fertiz.queryscope.agent.panel;

import com.sun.net.httpserver.HttpExchange;
import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.console.AiReport;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.http.HttpSupport;
import me.fertiz.queryscope.agent.http.JsonReader;
import me.fertiz.queryscope.agent.http.LocalServer;
import me.fertiz.queryscope.agent.loadtest.LoadTester;
import me.fertiz.queryscope.agent.methodtrace.MethodTracer;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static me.fertiz.queryscope.agent.http.HttpSupport.JSON;
import static me.fertiz.queryscope.agent.http.HttpSupport.TEXT;
import static me.fertiz.queryscope.agent.http.HttpSupport.errorJson;
import static me.fertiz.queryscope.agent.http.HttpSupport.readBody;
import static me.fertiz.queryscope.agent.http.HttpSupport.requireJsonFromSameOrigin;
import static me.fertiz.queryscope.agent.http.HttpSupport.requireMethod;
import static me.fertiz.queryscope.agent.http.HttpSupport.respond;
import static me.fertiz.queryscope.agent.http.HttpSupport.sendStatus;

public final class PanelServer {

    private static final Map<String, String> ASSETS = Map.of(
            "/", "index.html",
            "/panel.css", "panel.css",
            "/panel.js", "panel.js",
            "/common.js", "common.js");
    private static final long HEARTBEAT_SECONDS = 15;
    private static final int MAX_PENDING_EVENTS = 500;
    private static final Pattern CALLS_PATH = Pattern.compile("/api/requests/(\\d{1,18})/calls");
    private static final Pattern TRACE_REQUEST =
            Pattern.compile("\\{\\s*\"method\"\\s*:\\s*\"([^\"\\\\]{1,500})\"\\s*}");
    private static final String TRACES_PATH = "/api/traces";
    private static final String LOAD_TESTS_PATH = "/api/load-tests";
    private static final String MARKDOWN = "text/markdown; charset=utf-8";
    private static final long REPORT_WINDOW_MILLIS = 5 * 60 * 1000;
    private static final int MAX_BODY = 1024;

    private final ReportStore store;
    private final AppPackages callTreePackages;
    private final AppPackages application;
    private final SampleStore samples;
    private final MethodTracer tracer;
    private final LoadTester loadTester;

    private PanelServer(ReportStore store, AppPackages callTreePackages, AppPackages application, SampleStore samples,
                        MethodTracer tracer, LoadTester loadTester) {
        this.store = store;
        this.callTreePackages = callTreePackages;
        this.application = application;
        this.samples = samples;
        this.tracer = tracer;
        this.loadTester = loadTester;
    }

    public static LocalServer start(int port, boolean portGiven, ReportStore store, AppPackages callTreePackages,
                                    AppPackages application, SampleStore samples, MethodTracer tracer,
                                    LoadTester loadTester) throws IOException {
        PanelServer panel = new PanelServer(store, callTreePackages, application, samples, tracer, loadTester);
        McpEndpoint mcp = new McpEndpoint(store, callTreePackages, tracer, loadTester, panel::aiReport);
        return LocalServer.start(port, portGiven, "queryscope-panel", Map.of(
                "/", HttpSupport.staticFiles(ASSETS),
                "/api/requests", panel::serveRequests,
                "/api/profile", panel::serveProfile,
                TRACES_PATH, panel::serveTraces,
                LOAD_TESTS_PATH, panel::serveLoadTests,
                "/api/report.md", panel::serveAiReport,
                "/api/events", panel::streamEvents,
                "/mcp", mcp::serve));
    }

    private void serveRequests(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/api/requests")) {
            serveCalls(exchange, path);
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "GET" -> respond(exchange, 200, JSON, ReportJson.reportsToJson(store.newestFirst()));
            case "DELETE" -> {
                store.clear();
                sendStatus(exchange, 204);
            }
            default -> sendStatus(exchange, 405);
        }
    }

    private void serveCalls(HttpExchange exchange, String path) throws IOException {
        Matcher calls = CALLS_PATH.matcher(path);
        if (!calls.matches()) {
            sendStatus(exchange, 404);
            return;
        }
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        Optional<UnitReport> report = store.find(Long.parseLong(calls.group(1)));
        if (report.isEmpty()) {
            sendStatus(exchange, 404);
        } else {
            respond(exchange, 200, JSON, ReportJson.callTreeToJson(report.get(), callTreePackages.names()));
        }
    }

    private void serveProfile(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        Optional<Profile.Filter> filter;
        try {
            filter = ProfileQuery.parse(exchange.getRequestURI().getRawQuery(), store, samples);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, TEXT, e.getMessage());
            return;
        }
        if (filter.isEmpty()) {
            sendStatus(exchange, 404);
            return;
        }
        Profile profile = Profile.build(samples, application, filter.get());
        respond(exchange, 200, JSON, ProfileJson.profileToJson(profile, samples, filter.get(), application.names()));
    }

    private void serveAiReport(HttpExchange exchange) throws IOException {
        if (requireMethod(exchange, "GET")) {
            respond(exchange, 200, MARKDOWN, aiReport());
        }
    }

    private String aiReport() {
        long now = samples.now();
        Profile.Filter recent = new Profile.Filter(now - REPORT_WINDOW_MILLIS, now, Profile.Filter.ALL_THREADS, false,
                false, List.of());
        return AiReport.markdown(store.newestFirst(), application.names(), Profile.build(samples, application, recent),
                samples.source(), tracer.snapshots());
    }

    private void serveTraces(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith(TRACES_PATH + "/")) {
            stopTrace(exchange, path.substring(TRACES_PATH.length() + 1));
            return;
        }
        if (!path.equals(TRACES_PATH)) {
            sendStatus(exchange, 404);
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "GET" -> respond(exchange, 200, JSON,
                    MethodTraceJson.tracesToJson(tracer.snapshots(), MethodTracer.MAX_TRACED_METHODS));
            case "POST" -> startTrace(exchange);
            default -> sendStatus(exchange, 405);
        }
    }

    private void startTrace(HttpExchange exchange) throws IOException {
        if (!requireJsonFromSameOrigin(exchange)) {
            return;
        }
        Matcher request = TRACE_REQUEST.matcher(readBody(exchange, MAX_BODY).trim());
        if (!request.matches()) {
            respond(exchange, 400, JSON,
                    errorJson("Send the method as {\"method\": \"com.acme.OrderService#findAll\"}"));
            return;
        }
        try {
            respond(exchange, 200, JSON, MethodTraceJson.startedToJson(tracer.start(request.group(1))));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, JSON, errorJson(e.getMessage()));
        } catch (IllegalStateException e) {
            respond(exchange, 409, JSON, errorJson(e.getMessage()));
        }
    }

    private void stopTrace(HttpExchange exchange, String method) throws IOException {
        if (!requireMethod(exchange, "DELETE")) {
            return;
        }
        if (!HttpSupport.sameOrigin(exchange)) {
            sendStatus(exchange, 403);
            return;
        }
        sendStatus(exchange, tracer.stop(method) ? 204 : 404);
    }

    private void serveLoadTests(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(LOAD_TESTS_PATH)) {
            sendStatus(exchange, 404);
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "GET" -> respond(exchange, 200, JSON, LoadTestJson.latestToJson(loadTester.latest()));
            case "POST" -> startLoadTest(exchange);
            case "DELETE" -> stopLoadTest(exchange);
            default -> sendStatus(exchange, 405);
        }
    }

    private void startLoadTest(HttpExchange exchange) throws IOException {
        if (!requireJsonFromSameOrigin(exchange)) {
            return;
        }
        try {
            if (!(JsonReader.parse(readBody(exchange, MAX_BODY)) instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException(
                        "Send the load test as {\"id\": 17, \"requests\": 100, \"concurrency\": 10}");
            }
            respond(exchange, 200, JSON, LoadTestJson.resultToJson(
                    loadTester.start(LoadTestRequest.plan(fields, store))));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, JSON, errorJson(e.getMessage()));
        } catch (IllegalStateException e) {
            respond(exchange, 409, JSON, errorJson(e.getMessage()));
        }
    }

    private void stopLoadTest(HttpExchange exchange) throws IOException {
        if (!HttpSupport.sameOrigin(exchange)) {
            sendStatus(exchange, 403);
            return;
        }
        sendStatus(exchange, loadTester.stop() ? 204 : 404);
    }

    private void streamEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        BlockingQueue<UnitReport> pending = new LinkedBlockingQueue<>(MAX_PENDING_EVENTS);
        Runnable unsubscribe = store.subscribe(pending::offer);
        try (OutputStream body = exchange.getResponseBody()) {
            writeEvent(body, ": connected\n\n");
            while (true) {
                UnitReport report = pending.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                writeEvent(body, report == null
                        ? ": heartbeat\n\n"
                        : "event: request\ndata: " + ReportJson.reportToJson(report) + "\n\n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            unsubscribe.run();
        }
    }

    private static void writeEvent(OutputStream body, String event) throws IOException {
        body.write(event.getBytes(StandardCharsets.UTF_8));
        body.flush();
    }
}
