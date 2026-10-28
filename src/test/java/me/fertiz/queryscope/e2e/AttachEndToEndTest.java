package me.fertiz.queryscope.e2e;

import me.fertiz.queryscope.demo.DemoApplication;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static me.fertiz.queryscope.e2e.DemoProcess.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachEndToEndTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hubAttachesToAnAppThatWasStartedWithoutQueryScope() throws Exception {
        int appPort = DemoProcess.freePort();
        int hubPort = DemoProcess.freePort();
        List<String> app = List.of(DemoProcess.JAVA, "-XX:+EnableDynamicAgentLoading",
                "-cp", DemoProcess.DEMO_CLASSPATH, DemoApplication.class.getName(), "--server.port=" + appPort);
        List<String> hub = List.of(DemoProcess.JAVA, "-jar", DemoProcess.AGENT_JAR, "--port=" + hubPort, "--open=false");

        try (DemoProcess demo = DemoProcess.start(app, Path.of("build", "e2e-attach-app.log"), appPort);
             DemoProcess ignored = DemoProcess.start(hub, Path.of("build", "e2e-attach-hub.log"), hubPort)) {
            long pid = demo.process().pid();
            assertTrue(get(hubPort, "/api/processes").body().contains("\"pid\":" + pid), "The hub does not list " + pid);

            int panelPort = attach(hubPort, pid);
            assertEquals(panelPort, attach(hubPort, pid));
            assertEquals(200, get(appPort, "/api/customers/naive").statusCode());

            JsonNode report = QueryScopeEndToEndTest.reportFor(panelPort, "/api/customers/naive");
            assertTrue(report.path("findings").toString().contains("\"type\":\"nPlusOne\""), report.toString());
            assertTrue(report.path("methods").asInt() > 0, report.toString());
            assertTrue(demo.output().contains("QueryScope panel is running at http://localhost:" + panelPort),
                    demo.output());
        }
    }

    private static int attach(int hubPort, long pid) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + hubPort + "/api/attach"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"pid\": " + pid + "}"))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).path("panelPort").asInt();
    }
}
