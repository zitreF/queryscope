package me.fertiz.queryscope.e2e;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class DemoProcess implements AutoCloseable {

    static final String JAVA = ProcessHandle.current().info().command().orElse("java");
    static final String AGENT_JAR = System.getProperty("queryscope.agentJar");
    static final String DEMO_CLASSPATH = System.getProperty("queryscope.demoClasspath");

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private final Process process;
    private final Path log;

    private DemoProcess(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    static DemoProcess start(List<String> command, Path log, int appPort) throws Exception {
        Files.createDirectories(log.toAbsolutePath().getParent());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        DemoProcess demo = new DemoProcess(process, log);
        demo.waitUntilResponds(appPort);
        return demo;
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static boolean responds(int port) throws InterruptedException {
        try {
            get(port, "/");
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    Process process() {
        return process;
    }

    String output() throws IOException {
        return Files.readString(log);
    }

    @Override
    public void close() throws InterruptedException {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }

    private void waitUntilResponds(int port) throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new AssertionError("Demo app exited during startup:\n" + tail());
            }
            if (responds(port)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Demo app did not start within " + STARTUP_TIMEOUT + ":\n" + tail());
    }

    private String tail() throws IOException {
        List<String> lines = Files.readAllLines(log);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }
}
