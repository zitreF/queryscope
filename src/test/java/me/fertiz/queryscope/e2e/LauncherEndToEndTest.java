package me.fertiz.queryscope.e2e;

import me.fertiz.queryscope.demo.DemoApplication;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static me.fertiz.queryscope.e2e.DemoProcess.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherEndToEndTest {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void launcherStartsTheAppWithTheAgentAndTakesItDownWithItself() throws Exception {
        int appPort = DemoProcess.freePort();
        int panelPort = DemoProcess.freePort();
        List<String> command = List.of(
                DemoProcess.JAVA, "-jar", DemoProcess.AGENT_JAR,
                "--port=" + panelPort,
                "-cp", DemoProcess.DEMO_CLASSPATH, DemoApplication.class.getName(),
                "--server.port=" + appPort);

        try (DemoProcess launcher = DemoProcess.start(command, Path.of("build", "e2e-launcher.log"), appPort)) {
            assertEquals(200, get(appPort, "/api/customers/naive").statusCode());
            JsonNode report = QueryScopeEndToEndTest.reportFor(panelPort, "/api/customers/naive");
            assertTrue(report.path("findings").toString().contains("\"type\":\"nPlusOne\""), report.toString());

            launcher.process().destroy();

            assertTrue(stopsResponding(appPort), "The app kept running after the launcher was gone");
            assertTrue(printsSummary(launcher), launcher.output());
            assertTrue(launcher.output().matches("(?s).*GET /api/customers/naive +1 +41\\.0 .*"), launcher.output());
        }
    }

    private static boolean stopsResponding(int port) throws InterruptedException {
        Instant deadline = Instant.now().plus(SHUTDOWN_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!DemoProcess.responds(port)) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static boolean printsSummary(DemoProcess launcher) throws Exception {
        Instant deadline = Instant.now().plus(SHUTDOWN_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (launcher.output().contains("Findings, most frequent first")) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
