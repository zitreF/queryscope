package me.fertiz.queryscope.agent.panel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import me.fertiz.queryscope.agent.data.RequestReport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PanelServer {

    private static final Map<String, Asset> ASSETS = Map.of(
            "/", new Asset("index.html", "text/html; charset=utf-8"),
            "/panel.css", new Asset("panel.css", "text/css; charset=utf-8"),
            "/panel.js", new Asset("panel.js", "text/javascript; charset=utf-8"));
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final String JSON = "application/json; charset=utf-8";
    private static final long HEARTBEAT_SECONDS = 15;
    private static final int MAX_PENDING_EVENTS = 500;

    private final HttpServer server;
    private final ExecutorService executor;
    private final ReportStore store;

    private PanelServer(HttpServer server, ExecutorService executor, ReportStore store) {
        this.server = server;
        this.executor = executor;
        this.store = store;
    }

    public static PanelServer start(int port, ReportStore store) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        ExecutorService executor = Executors.newCachedThreadPool(daemonThreads());
        PanelServer panel = new PanelServer(server, executor, store);
        server.createContext("/", localOnly(panel::serveAsset));
        server.createContext("/api/requests", localOnly(panel::serveRequests));
        server.createContext("/api/events", localOnly(panel::streamEvents));
        server.setExecutor(executor);
        Thread starter = new Thread(server::start, "queryscope-panel-start");
        starter.setDaemon(true);
        starter.start();
        return panel;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void serveAsset(HttpExchange exchange) throws IOException {
        Asset asset = ASSETS.get(exchange.getRequestURI().getPath());
        if (asset == null) {
            respond(exchange, 404, null, new byte[0]);
        } else if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, null, new byte[0]);
        } else {
            respond(exchange, 200, asset.contentType(), asset.content());
        }
    }

    private void serveRequests(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET" -> respond(exchange, 200, JSON, utf8(ReportJson.of(store.newestFirst())));
            case "DELETE" -> {
                store.clear();
                respond(exchange, 204, null, new byte[0]);
            }
            default -> respond(exchange, 405, null, new byte[0]);
        }
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

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static HttpHandler localOnly(HttpHandler handler) {
        return exchange -> {
            if (LOCAL_HOSTS.contains(hostName(exchange.getRequestHeaders().getFirst("Host")))) {
                handler.handle(exchange);
            } else {
                respond(exchange, 403, null, new byte[0]);
            }
        };
    }

    private static String hostName(String hostHeader) {
        if (hostHeader == null) {
            return "";
        }
        if (hostHeader.startsWith("[")) {
            return hostHeader.substring(0, hostHeader.indexOf(']') + 1);
        }
        int colon = hostHeader.indexOf(':');
        return colon < 0 ? hostHeader : hostHeader.substring(0, colon);
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "queryscope-panel-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private record Asset(String file, String contentType, byte[] content) {

        private Asset(String file, String contentType) {
            this(file, contentType, read(file));
        }

        private static byte[] read(String file) {
            try (InputStream in = PanelServer.class.getResourceAsStream(file)) {
                if (in == null) {
                    throw new IllegalStateException("Missing panel asset " + file);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
