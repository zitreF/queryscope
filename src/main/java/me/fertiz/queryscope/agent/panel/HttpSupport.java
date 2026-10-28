package me.fertiz.queryscope.agent.panel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class HttpSupport {

    static final String JSON = "application/json; charset=utf-8";
    static final String TEXT = "text/plain; charset=utf-8";

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private HttpSupport() {
    }

    static HttpServer loopbackServer(int port) throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    }

    static ExecutorService daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    static void startInBackground(HttpServer server, String threadName) {
        Thread starter = new Thread(server::start, threadName);
        starter.setDaemon(true);
        starter.start();
    }

    static void serveAsset(HttpExchange exchange, Map<String, Asset> assets) throws IOException {
        Asset asset = assets.get(exchange.getRequestURI().getPath());
        if (asset == null) {
            respond(exchange, 404, null, new byte[0]);
        } else if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, null, new byte[0]);
        } else {
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            respond(exchange, 200, asset.contentType(), asset.content());
        }
    }

    static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static HttpHandler localOnly(HttpHandler handler) {
        return exchange -> {
            if (LOCAL_HOSTS.contains(hostName(exchange.getRequestHeaders().getFirst("Host")))) {
                handler.handle(exchange);
            } else {
                respond(exchange, 403, null, new byte[0]);
            }
        };
    }

    static boolean sameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String host = exchange.getRequestHeaders().getFirst("Host");
        return origin == null || origin.equals("http://" + host);
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
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

    record Asset(String contentType, byte[] content) {

        static Asset of(String file, String contentType) {
            try (InputStream in = HttpSupport.class.getResourceAsStream(file)) {
                if (in == null) {
                    throw new IllegalStateException("Missing panel asset " + file);
                }
                return new Asset(contentType, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
