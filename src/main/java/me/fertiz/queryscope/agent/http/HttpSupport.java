package me.fertiz.queryscope.agent.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class HttpSupport {

    public static final String JSON = "application/json; charset=utf-8";
    public static final String TEXT = "text/plain; charset=utf-8";

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final String WEB_ASSETS = "/me/fertiz/queryscope/agent/web/";
    private static final Map<String, String> ASSET_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8");

    private HttpSupport() {
    }

    public static HttpHandler staticFiles(Map<String, String> filesByPath) {
        Map<String, Asset> assets = new HashMap<>();
        filesByPath.forEach((path, file) -> assets.put(path, Asset.load(file)));
        return exchange -> {
            Asset asset = assets.get(exchange.getRequestURI().getPath());
            if (asset == null) {
                sendStatus(exchange, 404);
            } else if (requireMethod(exchange, "GET")) {
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
                exchange.getResponseHeaders().set("Content-Security-Policy", "frame-ancestors 'none'");
                sendBytes(exchange, 200, asset.type(), asset.content());
            }
        };
    }

    public static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (exchange.getRequestMethod().equals(method)) {
            return true;
        }
        sendStatus(exchange, 405);
        return false;
    }

    public static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    public static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        sendBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean requireJsonFromSameOrigin(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/json")) {
            sendStatus(exchange, 415);
            return false;
        }
        if (!sameOrigin(exchange)) {
            sendStatus(exchange, 403);
            return false;
        }
        return true;
    }

    public static String readBody(HttpExchange exchange, int maxBytes) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8);
        }
    }

    public static String errorJson(String message) {
        return new JsonWriter().beginObject().name("error").value(message).endObject().toString();
    }

    public static boolean sameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String host = exchange.getRequestHeaders().getFirst("Host");
        return origin == null || origin.equals("http://" + host);
    }

    static HttpHandler localhostOnly(HttpHandler handler) {
        return exchange -> {
            if (LOCAL_HOSTS.contains(hostName(exchange.getRequestHeaders().getFirst("Host")))) {
                handler.handle(exchange);
            } else {
                sendStatus(exchange, 403);
            }
        };
    }

    private static void sendBytes(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
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

    private record Asset(String type, byte[] content) {

        private static Asset load(String file) {
            String type = ASSET_TYPES.get(file.substring(file.lastIndexOf('.') + 1));
            try (InputStream in = HttpSupport.class.getResourceAsStream(WEB_ASSETS + file)) {
                if (in == null || type == null) {
                    throw new IllegalStateException("Missing panel asset " + file);
                }
                return new Asset(type, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
