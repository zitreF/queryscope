package me.fertiz.queryscope.agent.panel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.profile.Profile;
import me.fertiz.queryscope.agent.profile.SampleStore;
import me.fertiz.queryscope.agent.trace.AppPackages;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static me.fertiz.queryscope.agent.panel.HttpSupport.JSON;
import static me.fertiz.queryscope.agent.panel.HttpSupport.TEXT;
import static me.fertiz.queryscope.agent.panel.HttpSupport.localOnly;
import static me.fertiz.queryscope.agent.panel.HttpSupport.respond;
import static me.fertiz.queryscope.agent.panel.HttpSupport.utf8;

public final class PanelServer {

    private static final Map<String, HttpSupport.Asset> ASSETS = Map.of(
            "/", HttpSupport.Asset.of("index.html", "text/html; charset=utf-8"),
            "/panel.css", HttpSupport.Asset.of("panel.css", "text/css; charset=utf-8"),
            "/panel.js", HttpSupport.Asset.of("panel.js", "text/javascript; charset=utf-8"));
    private static final long HEARTBEAT_SECONDS = 15;
    private static final int MAX_PENDING_EVENTS = 500;
    private static final Pattern CALLS_PATH = Pattern.compile("/api/requests/(\\d{1,18})/calls");

    private final HttpServer server;
    private final ExecutorService executor;
    private final ReportStore store;
    private final List<String> profiledPackages;
    private final AppPackages application;
    private final SampleStore samples;

    private PanelServer(HttpServer server, ExecutorService executor, ReportStore store, List<String> profiledPackages,
                        AppPackages application, SampleStore samples) {
        this.server = server;
        this.executor = executor;
        this.store = store;
        this.profiledPackages = List.copyOf(profiledPackages);
        this.application = application;
        this.samples = samples;
    }

    public static PanelServer start(int port, ReportStore store, List<String> profiledPackages,
                                    AppPackages application, SampleStore samples) throws IOException {
        HttpServer server = HttpSupport.loopbackServer(port);
        ExecutorService executor = HttpSupport.daemonThreads("queryscope-panel-");
        PanelServer panel = new PanelServer(server, executor, store, profiledPackages, application, samples);
        server.createContext("/", localOnly(exchange -> HttpSupport.serveAsset(exchange, ASSETS)));
        server.createContext("/api/requests", localOnly(panel::serveRequests));
        server.createContext("/api/profile", localOnly(panel::serveProfile));
        server.createContext("/api/events", localOnly(panel::streamEvents));
        server.setExecutor(executor);
        HttpSupport.startInBackground(server, "queryscope-panel-start");
        return panel;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void serveRequests(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/api/requests")) {
            serveCalls(exchange, CALLS_PATH.matcher(path));
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "GET" -> respond(exchange, 200, JSON, utf8(ReportJson.of(store.newestFirst())));
            case "DELETE" -> {
                store.clear();
                respond(exchange, 204, null, new byte[0]);
            }
            default -> respond(exchange, 405, null, new byte[0]);
        }
    }

    private void serveCalls(HttpExchange exchange, Matcher path) throws IOException {
        if (!path.matches()) {
            respond(exchange, 404, null, new byte[0]);
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, null, new byte[0]);
            return;
        }
        Optional<RequestReport> report = store.find(Long.parseLong(path.group(1)));
        if (report.isEmpty()) {
            respond(exchange, 404, null, new byte[0]);
        } else {
            respond(exchange, 200, JSON, utf8(ReportJson.ofCalls(report.get(), profiledPackages)));
        }
    }

    private void serveProfile(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, null, new byte[0]);
            return;
        }
        Optional<Profile.Filter> filter;
        try {
            filter = ProfileQuery.parse(exchange.getRequestURI().getRawQuery(), store, samples);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, TEXT, utf8(e.getMessage()));
            return;
        }
        if (filter.isEmpty()) {
            respond(exchange, 404, null, new byte[0]);
            return;
        }
        Profile profile = Profile.build(samples, application, filter.get());
        respond(exchange, 200, JSON, utf8(ProfileJson.of(profile, samples, filter.get(), application.names())));
    }

    private void streamEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        BlockingQueue<RequestReport> pending = new LinkedBlockingQueue<>(MAX_PENDING_EVENTS);
        Runnable unsubscribe = store.subscribe(pending::offer);
        try (OutputStream body = exchange.getResponseBody()) {
            send(body, ": connected\n\n");
            while (!Thread.currentThread().isInterrupted()) {
                RequestReport report = pending.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                send(body, report == null ? ": heartbeat\n\n" : "event: request\ndata: " + ReportJson.of(report) + "\n\n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            unsubscribe.run();
            exchange.close();
        }
    }

    private static void send(OutputStream body, String event) throws IOException {
        body.write(utf8(event));
        body.flush();
    }
}
