package me.fertiz.queryscope.agent.panel;

import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.http.LocalServer;
import me.fertiz.queryscope.agent.profiler.SampleStore;
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
import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final ReportStore store = new ReportStore(10);
    private final SampleStore samples = new SampleStore(Clock.systemUTC(), 100, Duration.ofMinutes(5));
    private LocalServer panel;

    @BeforeEach
    void startPanel() throws IOException {
        AppPackages acme = AppPackages.of(List.of("com.acme"));
        panel = PanelServer.start(0, true, store, acme, acme, samples);
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
        assertEquals("no-cache", page.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElseThrow());
        assertEquals("frame-ancestors 'none'", page.headers().firstValue("Content-Security-Policy").orElseThrow());
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
    void servesTheCallTreeOfOneStoredRequest() throws Exception {
        store.add(Reports.plain(5, "/orders"));

        HttpResponse<String> calls = get("/api/requests/5/calls");

        assertEquals(200, calls.statusCode());
        assertTrue(calls.body().startsWith("{\"id\":5,\"durationMs\":2.000,\"packages\":[\"com.acme\"]"), calls.body());
        assertEquals(404, get("/api/requests/6/calls").statusCode());
        assertEquals(404, get("/api/requests/5/queries").statusCode());
        assertEquals(405, client.send(request("/api/requests/5/calls").DELETE().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void servesAFlameGraphOfTheSampledStacks() throws Exception {
        samples.markStarted("JDK Flight Recorder", Duration.ofMillis(10));
        samples.add(System.currentTimeMillis(), 1, "main", true, false,
                List.of("java.lang.Thread.run", "com.acme.Report.render"));

        HttpResponse<String> profile = get("/api/profile?window=60&frames=app");

        assertEquals(200, profile.statusCode());
        assertTrue(profile.body().startsWith("{\"source\":\"JDK Flight Recorder\",\"packages\":[\"com.acme\"]"),
                profile.body());
        assertTrue(profile.body().contains("\"samples\":1,\"agentSamples\":0,"), profile.body());
        assertTrue(profile.body().contains("{\"method\":\"com.acme.Report.render\",\"app\":true,\"total\":1,\"self\":1"),
                profile.body());
        assertEquals(400, get("/api/profile?frames=some").statusCode());
        assertEquals(404, get("/api/profile?request=42").statusCode());
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
            assertEquals("data: " + ReportJson.reportToJson(Reports.plain(3, "/live")), lines.next());
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
