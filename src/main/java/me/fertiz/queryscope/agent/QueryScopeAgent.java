package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.calltree.AppPackages;
import me.fertiz.queryscope.agent.console.ConsoleReporter;
import me.fertiz.queryscope.agent.console.SessionSummary;
import me.fertiz.queryscope.agent.findings.QueryAnalyzer;
import me.fertiz.queryscope.agent.findings.RequestReport;
import me.fertiz.queryscope.agent.http.Browser;
import me.fertiz.queryscope.agent.http.LocalServer;
import me.fertiz.queryscope.agent.launch.LaunchCommand;
import me.fertiz.queryscope.agent.panel.PanelServer;
import me.fertiz.queryscope.agent.panel.ReportStore;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;
import me.fertiz.queryscope.agent.profiler.Sampling;
import me.fertiz.queryscope.agent.trace.RequestTrace;
import me.fertiz.queryscope.agent.trace.RequestTracer;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QueryScopeAgent {

    public static final String PANEL_PORT_PROPERTY = "queryscope.panelPort";
    public static final String PACKAGE = QueryScopeAgent.class.getPackageName() + ".";

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final int STORED_REQUESTS = 200;
    private static final int STORED_SAMPLES = 200_000;
    private static final Duration SAMPLE_AGE = Duration.ofMinutes(15);
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private QueryScopeAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        start(arguments, instrumentation);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        start(arguments, instrumentation);
    }

    private static void start(String arguments, Instrumentation instrumentation) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        AgentConfig config = AgentConfig.parse(arguments);
        startOrTurnOff(() -> startWith(config, instrumentation));
    }

    static void startOrTurnOff(Runnable startup) {
        try {
            startup.run();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(System.Logger.Level.WARNING, "QueryScope is off: " + e);
        }
    }

    private static void startWith(AgentConfig config, Instrumentation instrumentation) {
        exitWithLauncher();
        QueryAnalyzer analyzer = new QueryAnalyzer(config.thresholds());
        ConsoleReporter console = new ConsoleReporter();
        ReportStore store = new ReportStore(STORED_REQUESTS);
        SessionSummary summary = new SessionSummary(Clock.systemUTC());
        RequestTracer.publishTo(trace -> publish(trace, analyzer, console, store, summary));
        AppPackages application = config.packages().isEmpty() ? AppPackages.detect() : AppPackages.of(config.packages());
        AppPackages callTreePackages = config.callTree() ? application : AppPackages.of(List.of());
        if (config.callTree()) {
            logCallTree(application);
        }
        AgentInstaller.install(instrumentation, config.entryMethods(), callTreePackages);
        SampleStore samples = new SampleStore(Clock.systemUTC(), STORED_SAMPLES, SAMPLE_AGE);
        Sampling sampling = config.sampling() ? Sampling.start(samples, config.sampleInterval()) : null;
        startPanel(config, store, callTreePackages, application, samples);
        if (config.printSummary()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (sampling != null) {
                    sampling.stop();
                }
                summary.print(System.out, Profile.build(samples, application, Profile.Filter.everything()));
            }, "queryscope-summary"));
        }
    }

    private static void publish(RequestTrace trace, QueryAnalyzer analyzer, ConsoleReporter console, ReportStore store,
                                SessionSummary summary) {
        boolean ranSql = !trace.queries().isEmpty();
        if (!ranSql && trace.callTree().calls().isEmpty()) {
            return;
        }
        RequestReport report = analyzer.analyze(trace);
        if (ranSql || !report.findings().isEmpty()) {
            console.log(report);
        }
        store.add(report);
        summary.accept(report);
    }

    private static void logCallTree(AppPackages application) {
        LOGGER.log(System.Logger.Level.INFO, application.isEmpty()
                ? "QueryScope could not tell the application's package, so the call tree is off."
                + " Pass package=com.acme to turn it on."
                : "QueryScope records the call tree of " + String.join(", ", application.names()));
    }

    private static void exitWithLauncher() {
        String launcherPid = System.getProperty(LaunchCommand.LAUNCHER_PID_PROPERTY);
        if (launcherPid != null) {
            ProcessHandle.of(Long.parseLong(launcherPid))
                    .ifPresent(launcher -> launcher.onExit().thenRun(() -> System.exit(0)));
        }
    }

    private static void startPanel(AgentConfig config, ReportStore store, AppPackages callTreePackages,
                                   AppPackages application, SampleStore samples) {
        try {
            LocalServer panel = PanelServer.start(config.port(), config.portGiven(), store, callTreePackages,
                    application, samples);
            String url = "http://localhost:" + panel.port();
            System.setProperty(PANEL_PORT_PROPERTY, String.valueOf(panel.port()));
            LOGGER.log(System.Logger.Level.INFO, "QueryScope panel is running at " + url);
            if (config.openPanel()) {
                Browser.open(url);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "QueryScope panel could not start on port " + config.port() + ", queries are logged only: " + e);
        }
    }
}
