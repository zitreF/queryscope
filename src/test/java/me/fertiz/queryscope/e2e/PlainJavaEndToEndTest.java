package me.fertiz.queryscope.e2e;

import me.fertiz.queryscope.plain.PlainHttpServerApp;
import me.fertiz.queryscope.plain.PlainJdbcApp;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainJavaEndToEndTest {

    @Test
    void tracesAPlainJavaProgramAndPrintsTheSummaryWhenItExits() throws Exception {
        String output = run("", "e2e-plain.log");

        assertTrue(output.contains("QueryScope records the call tree of me.fertiz.queryscope.plain"), output);
        assertTrue(output.contains("Task PlainJdbcApp.main, 12 queries"), output);
        assertTrue(output.contains("N+1: 10 x select title from books where id = ? at "
                + "me.fertiz.queryscope.plain.PlainJdbcApp.title(PlainJdbcApp.java:"), output);
        assertTrue(output.contains("QueryScope session summary: "), output);
        assertTrue(output.matches("(?s).*Task PlainJdbcApp\\.main +1 +12\\.0 .* N\\+1.*"), output);
        assertTrue(output.contains("1 x  N+1: 10 x select title from books where id = ?"), output);
    }

    @Test
    void mentionsATaskThatWasStillOpenWhenTheProgramExited() throws Exception {
        String output = run("", "e2e-plain-open.log", PlainJdbcApp.EXIT_WITHOUT_CLOSING);

        assertTrue(output.contains("0 requests and tasks"), output);
        assertTrue(output.contains("1 task was still open when the JVM stopped and is not counted"), output);
    }

    @Test
    void tracesRequestsOfTheJdkHttpServer() throws Exception {
        String output = run(PlainHttpServerApp.class, "", "e2e-plain-http-server.log");

        assertTrue(output.contains("Books: 3"), output);
        assertTrue(output.contains("GET /books -> 200, 1 query in"), output);
        assertFalse(output.contains("cannot see requests of the JDK's HttpServer"), output);
    }

    @Test
    void skipsTheSummaryWhenAskedTo() throws Exception {
        String output = run(",summary=false", "e2e-plain-no-summary.log");

        assertTrue(output.contains("Task PlainJdbcApp.main, 12 queries"), output);
        assertFalse(output.contains("QueryScope session summary"), output);
    }

    private static String run(String options, String logName, String... programArguments) throws Exception {
        return run(PlainJdbcApp.class, options, logName, programArguments);
    }

    private static String run(Class<?> program, String options, String logName, String... programArguments)
            throws Exception {
        Path log = Path.of("build", logName);
        Files.createDirectories(log.toAbsolutePath().getParent());
        List<String> command = new ArrayList<>(List.of(
                DemoProcess.JAVA,
                "-javaagent:" + DemoProcess.AGENT_JAR + "=port=" + DemoProcess.freePort() + options,
                "-cp", DemoProcess.DEMO_CLASSPATH, program.getName()));
        command.addAll(List.of(programArguments));
        Process process = new ProcessBuilder(command)
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
