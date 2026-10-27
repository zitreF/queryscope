package me.fertiz.queryscope.agent.launch;

import me.fertiz.queryscope.agent.AgentConfig;

import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class QueryScopeLauncher {

    private static final long STOP_TIMEOUT_SECONDS = 15;
    private static final int USAGE_ERROR = 2;
    private static final String USAGE = """
            Usage: java -jar queryscope.jar [options] <app.jar> [app arguments]
                   java -jar queryscope.jar [options] -cp <classpath> <main class> [app arguments]

            Starts the application with the QueryScope agent attached and stops it on Ctrl+C.

            Options:
              --port=7070                 port of the panel on localhost
              --open                      opens the panel in the browser once it is running
              --summary=false             skips the session summary printed when the application stops
              --nPlusOneThreshold=5       runs of the same SELECT, or write, in one request that are reported
              --slowQueryMs=100           queries at least this slow are reported
              --largeResultRows=500       queries returning at least this many rows are reported
              --maxQueries=30             requests running at least this many queries are reported
              --slowMethodMs=100          methods spending at least this long in their own code are reported
              --package=com.acme          package whose methods go into the call tree, can be repeated,
                                          by default the package of the application's main class
              --profile=false             turns the call tree off
              --entry=com.acme.Job#run    method whose call becomes one task, can be repeated

            JVM options of this java command, such as -Xmx1g or -Dspring.profiles.active=local, are
            passed on to the application.
            """;

    private QueryScopeLauncher() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0 || List.of(arguments).contains("--help")) {
            System.out.print(USAGE);
            return;
        }
        LaunchCommand command;
        try {
            command = LaunchCommand.parse(arguments);
            AgentConfig.parse(command.agentOptionString());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.print(USAGE);
            System.exit(USAGE_ERROR);
            return;
        }
        Process application = new ProcessBuilder(command.processCommand(
                javaExecutable(), agentJar(), jvmOptions(), ProcessHandle.current().pid()))
                .inheritIO()
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(application), "queryscope-launcher-stop"));
        System.exit(application.waitFor());
    }

    private static void stop(Process application) {
        if (!application.isAlive()) {
            return;
        }
        if (application.supportsNormalTermination()) {
            application.destroy();
        }
        try {
            if (!application.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                application.destroyForcibly();
            }
        } catch (InterruptedException e) {
            application.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static Path agentJar() throws URISyntaxException {
        return Path.of(QueryScopeLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static List<String> jvmOptions() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }
}
