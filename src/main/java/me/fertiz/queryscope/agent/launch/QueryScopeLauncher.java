package me.fertiz.queryscope.agent.launch;

import me.fertiz.queryscope.agent.AgentConfig;
import me.fertiz.queryscope.agent.hub.HubCommand;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class QueryScopeLauncher {

    private static final long STOP_TIMEOUT_SECONDS = 15;
    private static final int USAGE_ERROR = 2;
    private static final String USAGE = """
            Usage: java -jar queryscope.jar [options]
                   java -jar queryscope.jar [options] --attach=<pid>
                   java -jar queryscope.jar [options] <app.jar> [app arguments]
                   java -jar queryscope.jar [options] -cp <classpath> <main class> [app arguments]

            Without an application, QueryScope starts its hub in the browser, which lists the Java
            processes on this computer and attaches to the one you pick. --attach does the same for
            one process from the command line. With an application, QueryScope starts it with the
            agent attached and stops it on Ctrl+C.

            Options:
              --port=7070                 port of the hub, or of the panel when starting an application
              --open                      opens the panel in the browser once it is running
              --open=false                keeps the hub from opening the browser
              --summary=false             skips the session summary printed when the application stops
              --nPlusOneThreshold=5       runs of the same SELECT, or write, in one request that are reported
              --slowQueryMs=100           queries at least this slow are reported
              --largeResultRows=500       queries returning at least this many rows are reported
              --maxQueries=30             requests running at least this many queries are reported
              --slowMethodMs=100          methods spending at least this long in their own code are reported
              --package=com.acme          package whose methods go into the call tree, can be repeated,
                                          by default the package of the application's main class
              --profile=false             turns the call tree off
              --sampleMs=10               how often the sampling profiler takes a stack sample
              --sampling=false            turns the sampling profiler off
              --entry=com.acme.Job#run    method whose call becomes one task, can be repeated

            JVM options of this java command, such as -Xmx1g or -Dspring.profiles.active=local, are
            passed on to the application, except other agents and debuggers.
            """;
    private static final String HELP = "help=true";

    private QueryScopeLauncher() {
    }

    public static void main(String[] arguments) throws Exception {
        LaunchCommand command = orUsage(() -> LaunchCommand.parse(arguments));
        if (command.agentOptions().contains(HELP)) {
            System.out.print(USAGE);
        } else if (command.target().isEmpty()) {
            runHub(orUsage(() -> HubCommand.parse(command.agentOptions())));
        } else {
            orUsage(() -> AgentConfig.parse(command.agentOptionString()));
            runApplication(command);
        }
    }

    private static <T> T orUsage(Supplier<T> parse) {
        try {
            return parse.get();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.print(USAGE);
            System.exit(USAGE_ERROR);
            return null;
        }
    }

    private static void runApplication(LaunchCommand command) throws Exception {
        Process application = new ProcessBuilder(command.processCommand(
                javaExecutable(), agentJar(), jvmOptions(), ProcessHandle.current().pid()))
                .inheritIO()
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(application), "queryscope-launcher-stop"));
        System.exit(application.waitFor());
    }

    private static void runHub(HubCommand hub) throws Exception {
        try {
            hub.run(agentJar());
        } catch (LinkageError e) {
            System.err.println("Attaching needs a JDK with the jdk.attach module, and " + System.getProperty("java.home")
                    + " has none. Run queryscope.jar with a JDK.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
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
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        return LaunchCommand.withDeepJfrStacks(runtime.getInputArguments(), runtime.getVmName());
    }
}
