package me.fertiz.queryscope.agent.panel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.fertiz.queryscope.agent.attach.JavaProcess;
import me.fertiz.queryscope.agent.attach.Processes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static me.fertiz.queryscope.agent.panel.HttpSupport.JSON;
import static me.fertiz.queryscope.agent.panel.HttpSupport.localOnly;
import static me.fertiz.queryscope.agent.panel.HttpSupport.respond;
import static me.fertiz.queryscope.agent.panel.HttpSupport.utf8;

public final class HubServer {

    private static final Map<String, HttpSupport.Asset> ASSETS = Map.of(
            "/", HttpSupport.Asset.of("hub.html", "text/html; charset=utf-8"),
            "/hub.js", HttpSupport.Asset.of("hub.js", "text/javascript; charset=utf-8"),
            "/panel.css", HttpSupport.Asset.of("panel.css", "text/css; charset=utf-8"));
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d{1,18})");
    private static final int MAX_BODY = 1024;

    private final HttpServer server;
    private final ExecutorService executor;
    private final Processes processes;

    private HubServer(HttpServer server, ExecutorService executor, Processes processes) {
        this.server = server;
        this.executor = executor;
        this.processes = processes;
    }

    public static HubServer start(int port, Processes processes) throws IOException {
        HttpServer server = HttpSupport.loopbackServer(port);
        ExecutorService executor = HttpSupport.daemonThreads("queryscope-hub-");
        HubServer hub = new HubServer(server, executor, processes);
        server.createContext("/", localOnly(exchange -> HttpSupport.serveAsset(exchange, ASSETS)));
        server.createContext("/api/processes", localOnly(hub::serveProcesses));
        server.createContext("/api/attach", localOnly(hub::attach));
        server.setExecutor(executor);
        HttpSupport.startInBackground(server, "queryscope-hub-start");
        return hub;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void serveProcesses(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 405, null, new byte[0]);
            return;
        }
        List<JavaProcess> list = processes.list();
        JsonWriter json = new JsonWriter().beginArray();
        for (JavaProcess process : list) {
            json.beginObject()
                    .name("pid").value(process.pid())
                    .name("name").value(process.name())
                    .name("command").value(process.command())
                    .name("panelPort");
            if (process.attached()) {
                json.value(process.panelPort());
            } else {
                json.nullValue();
            }
            json.endObject();
        }
        respond(exchange, 200, JSON, utf8(json.endArray().toString()));
    }

    private void attach(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            respond(exchange, 405, null, new byte[0]);
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/json")) {
            respond(exchange, 415, null, new byte[0]);
            return;
        }
        if (!HttpSupport.sameOrigin(exchange)) {
            respond(exchange, 403, null, new byte[0]);
            return;
        }
        Matcher pid = PID.matcher(body(exchange));
        if (!pid.find()) {
            respond(exchange, 400, JSON, utf8(error("Send the process as {\"pid\": 1234}")));
            return;
        }
        long id = Long.parseLong(pid.group(1));
        try {
            int port = processes.attach(id);
            respond(exchange, 200, JSON, utf8(new JsonWriter().beginObject()
                    .name("pid").value(id)
                    .name("panelPort").value(port)
                    .endObject().toString()));
        } catch (IOException e) {
            respond(exchange, 409, JSON, utf8(error(e.getMessage())));
        }
    }

    private static String body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
        }
    }

    private static String error(String message) {
        return new JsonWriter().beginObject().name("error").value(message).endObject().toString();
    }
}
