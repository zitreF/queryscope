package me.fertiz.queryscope.agent.hub;

import com.sun.net.httpserver.HttpExchange;
import me.fertiz.queryscope.agent.http.HttpSupport;
import me.fertiz.queryscope.agent.http.Json;
import me.fertiz.queryscope.agent.http.LocalServer;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;
import java.util.OptionalLong;

import static me.fertiz.queryscope.agent.http.HttpSupport.JSON;
import static me.fertiz.queryscope.agent.http.HttpSupport.errorJson;
import static me.fertiz.queryscope.agent.http.HttpSupport.readBody;
import static me.fertiz.queryscope.agent.http.HttpSupport.requireJsonFromSameOrigin;
import static me.fertiz.queryscope.agent.http.HttpSupport.requireMethod;
import static me.fertiz.queryscope.agent.http.HttpSupport.respond;

public final class HubServer {

    private static final Map<String, String> ASSETS = Map.of(
            "/", "hub.html",
            "/hub.js", "hub.js",
            "/common.js", "common.js",
            "/panel.css", "panel.css");
    private static final int MAX_BODY = 1024;

    private final Processes processes;

    private HubServer(Processes processes) {
        this.processes = processes;
    }

    public static LocalServer start(int port, boolean portGiven, Processes processes) throws IOException {
        HubServer hub = new HubServer(processes);
        return LocalServer.start(port, portGiven, "queryscope-hub", Map.of(
                "/", HttpSupport.staticFiles(ASSETS),
                "/api/processes", hub::serveProcesses,
                "/api/attach", hub::serveAttach));
    }

    private void serveProcesses(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        respond(exchange, 200, JSON, Json.write(Json.array(processes.list(), HubServer::processJson)));
    }

    private static ObjectNode processJson(JavaProcess process) {
        ObjectNode json = Json.object()
                .put("pid", process.pid())
                .put("name", process.name())
                .put("command", process.command());
        return process.attached() ? json.put("panelPort", process.panelPort()) : json.putNull("panelPort");
    }

    private void serveAttach(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST") || !requireJsonFromSameOrigin(exchange)) {
            return;
        }
        OptionalLong pid = requestedPid(exchange);
        if (pid.isEmpty()) {
            respond(exchange, 400, JSON, errorJson("Send the process as {\"pid\": 1234}"));
            return;
        }
        long id = pid.getAsLong();
        try {
            int port = processes.attach(id);
            respond(exchange, 200, JSON, Json.write(Json.object()
                    .put("pid", id)
                    .put("panelPort", port)));
        } catch (IOException e) {
            respond(exchange, 409, JSON, errorJson(e.getMessage()));
        }
    }

    private static OptionalLong requestedPid(HttpExchange exchange) throws IOException {
        try {
            OptionalLong pid = Json.wholeNumber(Json.read(readBody(exchange, MAX_BODY)).path("pid"));
            return pid.isPresent() && pid.getAsLong() >= 0 ? pid : OptionalLong.empty();
        } catch (IllegalArgumentException e) {
            return OptionalLong.empty();
        }
    }
}
