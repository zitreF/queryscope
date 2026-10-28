package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.attach.JavaProcess;
import me.fertiz.queryscope.agent.attach.Processes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final FakeProcesses processes = new FakeProcesses();
    private HubServer hub;

    @BeforeEach
    void startHub() throws IOException {
        hub = HubServer.start(0, processes);
    }

    @AfterEach
    void stopHub() {
        hub.stop();
    }

    @Test
    void servesTheHubPage() throws Exception {
        HttpResponse<String> page = get("/");

        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Java processes on this computer"));
        assertEquals(200, get("/hub.js").statusCode());
        assertEquals(200, get("/panel.css").statusCode());
    }

    @Test
    void listsTheJavaProcesses() throws Exception {
        HttpResponse<String> list = get("/api/processes");

        assertEquals(200, list.statusCode());
        assertEquals("[{\"pid\":42,\"name\":\"shop.jar\",\"command\":\"shop.jar --debug\",\"panelPort\":null},"
                + "{\"pid\":43,\"name\":\"Worker\",\"command\":\"com.acme.Worker\",\"panelPort\":7171}]", list.body());
    }

    @Test
    void attachesToTheProcessItIsAskedFor() throws Exception {
        HttpResponse<String> attached = post("{\"pid\": 42}", "application/json");

        assertEquals(200, attached.statusCode());
        assertEquals("{\"pid\":42,\"panelPort\":7272}", attached.body());
        assertEquals(List.of(42L), processes.attached);
    }

    @Test
    void reportsWhyAnAttachFailed() throws Exception {
        HttpResponse<String> failed = post("{\"pid\": 13}", "application/json");

        assertEquals(409, failed.statusCode());
        assertEquals("{\"error\":\"Process 13 runs Java 11\"}", failed.body());
    }

    @Test
    void refusesRequestsThatAPageOnAnotherSiteCouldSend() throws Exception {
        HttpRequest foreign = HttpRequest.newBuilder(URI.create("http://localhost:" + hub.port() + "/api/attach"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://attacker.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"pid\": 42}"))
                .build();

        assertEquals(415, post("{\"pid\": 42}", "text/plain").statusCode());
        assertEquals(403, client.send(foreign, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(List.of(), processes.attached);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + hub.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + hub.port() + "/api/attach"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static final class FakeProcesses implements Processes {

        private final List<Long> attached = new ArrayList<>();

        @Override
        public List<JavaProcess> list() {
            return List.of(new JavaProcess(42, "shop.jar", "shop.jar --debug", JavaProcess.NOT_ATTACHED),
                    new JavaProcess(43, "Worker", "com.acme.Worker", 7171));
        }

        @Override
        public int attach(long pid) throws IOException {
            if (pid == 13) {
                throw new IOException("Process 13 runs Java 11");
            }
            attached.add(pid);
            return 7272;
        }
    }
}
