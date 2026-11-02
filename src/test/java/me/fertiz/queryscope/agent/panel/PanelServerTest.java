package me.fertiz.queryscope.agent.panel;

import com.sun.net.httpserver.HttpServer;
import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.http.HttpSupport;
import me.fertiz.queryscope.agent.http.LocalServer;
import me.fertiz.queryscope.agent.loadtest.LoadTester;
import me.fertiz.queryscope.agent.methodtrace.MethodTracer;
import me.fertiz.queryscope.agent.profiler.SampleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final ReportStore store = new ReportStore(10);
    private final SampleStore samples = new SampleStore(Clock.systemUTC(), 100, Duration.ofMinutes(5));
    private final MethodTracer tracer = new MethodTracer(() -> new Class<?>[0], (className, methodName) -> () -> {
    });
    private LocalServer panel;

    @BeforeEach
    void startPanel() throws IOException {
        AppPackages acme = AppPackages.of(List.of("com.acme"));
        panel = PanelServer.start(0, true, store, acme, acme, samples, tracer, new LoadTester());
    }

    @AfterEach
    void stopPanel() {
        tracer.snapshots().forEach(trace -> tracer.stop(trace.method()));
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
    void startsListsAndStopsMethodTraces() throws Exception {
        HttpResponse<String> started = postJson("/api/traces", "{\"method\": \"com.acme.OrderService#findAll\"}",
                null);

        assertEquals(200, started.statusCode());
        assertEquals("{\"method\":\"com.acme.OrderService.findAll\",\"loadedClasses\":0}", started.body());
        String traces = get("/api/traces").body();
        assertTrue(traces.startsWith("{\"limit\":20,\"traces\":[{\"method\":\"com.acme.OrderService.findAll\","), traces);
        assertEquals(204, delete("/api/traces/com.acme.OrderService.findAll").statusCode());
        assertEquals(404, delete("/api/traces/com.acme.OrderService.findAll").statusCode());
        assertEquals("{\"limit\":20,\"traces\":[]}", get("/api/traces").body());
    }

    @Test
    void startsTracesOnlyFromJsonSentByItsOwnPage() throws Exception {
        String body = "{\"method\": \"com.acme.OrderService#findAll\"}";

        assertEquals(415, client.send(request("/api/traces").POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "text/plain").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, postJson("/api/traces", body, "http://attacker.example").statusCode());
        assertEquals(400, postJson("/api/traces", "{\"method\": \"findAll\"}", null).statusCode());
        HttpResponse<String> jdk = postJson("/api/traces", "{\"method\": \"java.lang.String#length\"}", null);
        assertEquals(400, jdk.statusCode());
        assertTrue(jdk.body().contains("classes of the JDK and of QueryScope stay as they are"), jdk.body());
        assertEquals("{\"limit\":20,\"traces\":[]}", get("/api/traces").body());
    }

    @Test
    void loadTestsAGetRequestOnThePortItCameIn() throws Exception {
        Set<String> runs = ConcurrentHashMap.newKeySet();
        HttpServer application = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        application.createContext("/books", exchange -> {
            runs.add(exchange.getRequestURI() + " run "
                    + exchange.getRequestHeaders().getFirst("X-QueryScope-Load-Test"));
            HttpSupport.sendStatus(exchange, 204);
        });
        application.start();
        try {
            store.add(Reports.servedOnPort(3, "/books?page=2", application.getAddress().getPort()));

            HttpResponse<String> started = postJson("/api/load-tests",
                    "{\"id\": 3, \"requests\": 20, \"concurrency\": 4}", null);
            String test = awaitLoadTest();

            assertEquals(200, started.statusCode(), started.body());
            assertTrue(started.body().startsWith("{\"id\":1,\"endpoint\":\"GET /books\",\"method\":\"GET\","
                    + "\"target\":\"http://127.0.0.1:"), started.body());
            assertTrue(test.contains("\"answered\":20,\"failed\":0,\"firstError\":null"), test);
            assertTrue(test.contains("\"statuses\":[{\"status\":204,\"count\":20}]"), test);
            assertEquals(Set.of("/books?page=2 run 1"), runs);
            assertEquals(404, delete("/api/load-tests").statusCode());
        } finally {
            application.stop(0);
        }
    }

    @Test
    void loadTestsWithAnotherMethodHeadersAndABody() throws Exception {
        Set<String> received = ConcurrentHashMap.newKeySet();
        HttpServer application = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        application.createContext("/books", exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            HttpSupport.sendStatus(exchange, 201);
        });
        application.start();
        try {
            store.add(Reports.servedOnPort(3, "/books/7", application.getAddress().getPort()));

            HttpResponse<String> started = postJson("/api/load-tests", "{\"id\": 3, \"requests\": 5, "
                    + "\"concurrency\": 1, \"method\": \"POST\", \"path\": \"/books?draft=1\", "
                    + "\"headers\": {\"Authorization\": \"Bearer abc\"}, \"body\": \"{\\\"title\\\": 1}\"}", null);
            String test = awaitLoadTest();

            assertEquals(200, started.statusCode(), started.body());
            assertTrue(test.contains("\"endpoint\":\"POST /books\",\"method\":\"POST\""), test);
            assertTrue(test.contains("\"headerNames\":[\"Authorization\"],\"bodyBytes\":12"), test);
            assertFalse(test.contains("Bearer abc"), test);
            assertTrue(test.contains("\"statuses\":[{\"status\":201,\"count\":5}]"), test);
            assertEquals(Set.of("POST /books?draft=1 Bearer abc {\"title\": 1}"), received);
        } finally {
            application.stop(0);
        }
    }

    @Test
    void refusesLoadTestsItCannotRun() throws Exception {
        store.add(Reports.plain(1, "/orders"));

        HttpResponse<String> noPort = postJson("/api/load-tests", "{\"id\": 1}", null);

        assertEquals(400, noPort.statusCode());
        assertTrue(noPort.body().contains("does not know the port request 1 came in on"), noPort.body());
        assertEquals(400, postJson("/api/load-tests", "{\"id\": 99}", null).statusCode());
        assertEquals(400, postJson("/api/load-tests", "[1]", null).statusCode());
        assertEquals(403, postJson("/api/load-tests", "{\"id\": 1}", "http://attacker.example").statusCode());
        assertEquals(404, delete("/api/load-tests").statusCode());
        assertEquals("{\"maxRequests\":10000,\"maxConcurrency\":50,\"kept\":10,\"tests\":[]}",
                get("/api/load-tests").body());
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

    private String awaitLoadTest() throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            String latest = get("/api/load-tests").body();
            if (latest.contains("\"state\":\"finished\"")) {
                return latest;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The load test did not finish: " + get("/api/load-tests").body());
    }

    private HttpResponse<String> postJson(String path, String body, String origin) throws Exception {
        HttpRequest.Builder post = request(path).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (origin != null) {
            post.header("Origin", origin);
        }
        return client.send(post.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return client.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + panel.port() + path));
    }
}
