package me.fertiz.queryscope.agent.hub;

import com.sun.net.httpserver.HttpExchange;
import me.fertiz.queryscope.agent.http.HttpSupport;
import me.fertiz.queryscope.agent.http.JsonWriter;
import me.fertiz.queryscope.agent.http.LocalServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static me.fertiz.queryscope.agent.http.HttpSupport.JSON;
import static me.fertiz.queryscope.agent.http.HttpSupport.allow;
import static me.fertiz.queryscope.agent.http.HttpSupport.respond;
import static me.fertiz.queryscope.agent.http.HttpSupport.status;

public final class HubServer {

    private static final Map<String, String> ASSETS = Map.of(
            "/", "hub.html",
            "/hub.js", "hub.js",
            "/common.js", "common.js",
            "/panel.css", "panel.css");
    private static final Pattern PID = Pattern.compile("\\{\\s*\"pid\"\\s*:\\s*(\\d{1,18})\\s*}");
    private static final int MAX_BODY = 1024;

    private final Processes processes;

    private HubServer(Processes processes) {
        this.processes = processes;
    }

    public static LocalServer start(int port, boolean portGiven, Processes processes) throws IOException {
        HubServer hub = new HubServer(processes);
        return LocalServer.start(port, portGiven, "queryscope-hub", Map.of(
                "/", HttpSupport.assets(ASSETS),
                "/api/processes", hub::serveProcesses,
                "/api/attach", hub::attach));
    }

    private void serveProcesses(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) {
            return;
        }
        JsonWriter json = new JsonWriter().beginArray();
        for (JavaProcess process : processes.list()) {
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
        respond(exchange, 200, JSON, json.endArray().toString());
    }

    private void attach(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "POST")) {
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/json")) {
            status(exchange, 415);
            return;
        }
        if (!HttpSupport.sameOrigin(exchange)) {
            status(exchange, 403);
            return;
        }
        Matcher pid = PID.matcher(body(exchange).trim());
        if (!pid.matches()) {
            respond(exchange, 400, JSON, error("Send the process as {\"pid\": 1234}"));
            return;
        }
        long id = Long.parseLong(pid.group(1));
        try {
            int port = processes.attach(id);
            respond(exchange, 200, JSON, new JsonWriter().beginObject()
                    .name("pid").value(id)
                    .name("panelPort").value(port)
                    .endObject().toString());
        } catch (IOException e) {
            respond(exchange, 409, JSON, error(e.getMessage()));
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
