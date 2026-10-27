package me.fertiz.queryscope.e2e;

import me.fertiz.queryscope.plain.PlainJdbcApp;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainJavaEndToEndTest {

    @Test
    void tracesAPlainJavaProgramAndPrintsTheSummaryWhenItExits() throws Exception {
        String output = run("", "e2e-plain.log");

        assertTrue(output.contains("Task PlainJdbcApp.main, 12 queries"), output);
        assertTrue(output.contains("N+1: 10 x select title from books where id = ? at "
                + "me.fertiz.queryscope.plain.PlainJdbcApp.title(PlainJdbcApp.java:"), output);
        assertTrue(output.contains("QueryScope session summary: "), output);
        assertTrue(output.matches("(?s).*Task PlainJdbcApp\\.main +1 +12\\.0 .* N\\+1.*"), output);
        assertTrue(output.contains("1 x  N+1: 10 x select title from books where id = ?"), output);
    }

    @Test
    void skipsTheSummaryWhenAskedTo() throws Exception {
        String output = run(",summary=false", "e2e-plain-no-summary.log");

        assertTrue(output.contains("Task PlainJdbcApp.main, 12 queries"), output);
        assertFalse(output.contains("QueryScope session summary"), output);
    }

    private static String run(String options, String logName) throws Exception {
        Path log = Path.of("build", logName);
        Files.createDirectories(log.toAbsolutePath().getParent());
        Process process = new ProcessBuilder(List.of(
                DemoProcess.JAVA,
                "-javaagent:" + DemoProcess.AGENT_JAR + "=port=" + DemoProcess.freePort() + options,
                "-cp", DemoProcess.DEMO_CLASSPATH, PlainJdbcApp.class.getName()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertTrue(exited, "The program did not exit within a minute:\n" + output);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
