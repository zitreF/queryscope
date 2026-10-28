package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.analysis.QueryAnalyzer;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.instrument.AgentInstaller;
import me.fertiz.queryscope.agent.launch.LaunchCommand;
import me.fertiz.queryscope.agent.panel.Browser;
import me.fertiz.queryscope.agent.panel.PanelServer;
import me.fertiz.queryscope.agent.panel.ReportStore;
import me.fertiz.queryscope.agent.profile.Profile;
import me.fertiz.queryscope.agent.profile.SampleStore;
import me.fertiz.queryscope.agent.profile.Sampling;
import me.fertiz.queryscope.agent.report.ConsoleReporter;
import me.fertiz.queryscope.agent.report.SessionSummary;
import me.fertiz.queryscope.agent.trace.AppPackages;
import me.fertiz.queryscope.agent.trace.RequestTracer;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QueryScopeAgent {

    public static final String PANEL_PORT_PROPERTY = "queryscope.panelPort";

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
        QueryAnalyzer analyzer = new QueryAnalyzer(config.thresholds());
        ConsoleReporter console = new ConsoleReporter();
        ReportStore store = new ReportStore(STORED_REQUESTS);
        SessionSummary summary = new SessionSummary(Clock.systemUTC());
        RequestTracer.publishTo(trace -> {
            if (trace.queries().isEmpty() && trace.callTree().calls().isEmpty()) {
                return;
            }
            RequestReport report = analyzer.analyze(trace);
            if (!trace.queries().isEmpty() || !report.findings().isEmpty()) {
                console.accept(report);
            }
            store.add(report);
            summary.accept(report);
        });
        AppPackages application = config.packages().isEmpty() ? AppPackages.detect() : AppPackages.of(config.packages());
        AppPackages profiled = profiledPackages(config, application);
        AgentInstaller.install(instrumentation, config.entryMethods(), profiled);
        SampleStore samples = new SampleStore(Clock.systemUTC(), STORED_SAMPLES, SAMPLE_AGE);
        Sampling sampling = config.sampling() ? Sampling.start(samples, config.sampleInterval()) : null;
        startPanel(config, store, profiled, application, samples);
        if (config.printSummary()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (sampling != null) {
                    sampling.stop();
                }
                summary.print(System.out, Profile.build(samples, application, Profile.Filter.everything()));
            }, "queryscope-summary"));
        }
        exitWithLauncher();
    }

    private static void exitWithLauncher() {
        String launcherPid = System.getProperty(LaunchCommand.LAUNCHER_PID_PROPERTY);
        if (launcherPid != null) {
            ProcessHandle.of(Long.parseLong(launcherPid))
                    .ifPresent(launcher -> launcher.onExit().thenRun(() -> System.exit(0)));
        }
    }

    private static AppPackages profiledPackages(AgentConfig config, AppPackages application) {
        if (!config.profile()) {
            return AppPackages.of(List.of());
        }
        LOGGER.log(System.Logger.Level.INFO, application.isEmpty()
                ? "QueryScope could not tell the application's package, so the call tree is off."
                + " Pass package=com.acme to turn it on."
                : "QueryScope records the call tree of " + String.join(", ", application.names()));
        return application;
    }

    private static void startPanel(AgentConfig config, ReportStore store, AppPackages profiled, AppPackages application,
                                   SampleStore samples) {
        try {
            PanelServer panel = PanelServer.start(config.port(), store, profiled.names(), application, samples);
            String url = "http://localhost:" + panel.port();
            System.setProperty(PANEL_PORT_PROPERTY, String.valueOf(panel.port()));
            LOGGER.log(System.Logger.Level.INFO, "QueryScope panel is running at " + url);
            if (config.openPanel()) {
                Browser.open(url);
            }
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "QueryScope panel could not start on port " + config.port() + ", queries are logged only: " + e);
        }
    }
}
