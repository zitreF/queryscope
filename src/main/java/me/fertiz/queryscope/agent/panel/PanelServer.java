package me.fertiz.queryscope.agent.panel;

import com.sun.net.httpserver.HttpExchange;
import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.http.HttpSupport;
import me.fertiz.queryscope.agent.http.LocalServer;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static me.fertiz.queryscope.agent.http.HttpSupport.JSON;
import static me.fertiz.queryscope.agent.http.HttpSupport.TEXT;
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

    private final ReportStore store;
    private final AppPackages callTreePackages;
    private final AppPackages application;
    private final SampleStore samples;

    private PanelServer(ReportStore store, AppPackages callTreePackages, AppPackages application, SampleStore samples) {
        this.store = store;
        this.callTreePackages = callTreePackages;
        this.application = application;
        this.samples = samples;
    }

    public static LocalServer start(int port, boolean portGiven, ReportStore store, AppPackages callTreePackages,
                                    AppPackages application, SampleStore samples) throws IOException {
        PanelServer panel = new PanelServer(store, callTreePackages, application, samples);
        return LocalServer.start(port, portGiven, "queryscope-panel", Map.of(
                "/", HttpSupport.staticFiles(ASSETS),
                "/api/requests", panel::serveRequests,
                "/api/profile", panel::serveProfile,
                "/api/events", panel::streamEvents));
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
