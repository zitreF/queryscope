package me.fertiz.queryscope.agent.panel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final ReportStore store = new ReportStore(10);
    private PanelServer panel;

    @BeforeEach
    void startPanel() throws IOException {
        panel = PanelServer.start(0, store);
    }

    @AfterEach
    void stopPanel() {
        panel.stop();
    }

    @Test
    void servesThePanelPage() throws Exception {
        HttpResponse<String> page = get("/");

        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>QueryScope</title>"));
        assertEquals(404, get("/missing.js").statusCode());
    }

    @Test
    void listsStoredRequestsAndClearsThem() throws Exception {
        store.add(Reports.plain(1, "/orders"));

        HttpResponse<String> requests = get("/api/requests");
        HttpResponse<Void> cleared = client.send(request("/api/requests").DELETE().build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(200, requests.statusCode());
        assertTrue(requests.body().contains("\"path\":\"/orders\""));
        assertEquals(204, cleared.statusCode());
        assertTrue(store.newestFirst().isEmpty());
    }

    @Test
    void streamsNewRequestsAsServerSentEvents() throws Exception {
        HttpResponse<Stream<String>> events = client.send(request("/api/events").build(),
                HttpResponse.BodyHandlers.ofLines());
        Iterator<String> lines = events.body().iterator();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(": connected", lines.next());
            assertEquals("", lines.next());
            store.add(Reports.plain(3, "/live"));
            assertEquals("event: request", lines.next());
            assertEquals("data: " + ReportJson.of(Reports.plain(3, "/live")), lines.next());
        });
        assertEquals("text/event-stream; charset=utf-8", events.headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void rejectsRequestsForOtherHostNames() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", panel.port())) {
            OutputStream out = socket.getOutputStream();
            out.write("GET /api/requests HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

            assertEquals("HTTP/1.1 403 Forbidden", in.readLine());
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + panel.port() + path));
    }
}
