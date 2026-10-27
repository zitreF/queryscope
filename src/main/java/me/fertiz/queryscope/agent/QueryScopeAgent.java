package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.analysis.QueryAnalyzer;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.instrument.AgentInstaller;
import me.fertiz.queryscope.agent.launch.LaunchCommand;
import me.fertiz.queryscope.agent.panel.Browser;
import me.fertiz.queryscope.agent.panel.PanelServer;
import me.fertiz.queryscope.agent.panel.ReportStore;
import me.fertiz.queryscope.agent.report.ConsoleReporter;
import me.fertiz.queryscope.agent.report.SessionSummary;
import me.fertiz.queryscope.agent.trace.AppPackages;
import me.fertiz.queryscope.agent.trace.RequestTracer;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.time.Clock;
import java.util.List;

public final class QueryScopeAgent {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final int STORED_REQUESTS = 200;

    private QueryScopeAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        AgentConfig config = AgentConfig.parse(arguments);
        QueryAnalyzer analyzer = new QueryAnalyzer(config.thresholds());
        ConsoleReporter console = new ConsoleReporter();
        ReportStore store = new ReportStore(STORED_REQUESTS);
        SessionSummary summary = new SessionSummary(Clock.systemUTC());
        RequestTracer.publishTo(trace -> {
            if (!trace.queries().isEmpty()) {
                RequestReport report = analyzer.analyze(trace);
                console.accept(report);
                store.add(report);
                summary.accept(report);
            }
        });
        AppPackages profiled = profiledPackages(config);
        AgentInstaller.install(instrumentation, config.entryMethods(), profiled);
        startPanel(config, store, profiled);
        if (config.printSummary()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> summary.print(System.out), "queryscope-summary"));
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

    private static AppPackages profiledPackages(AgentConfig config) {
        if (!config.profile()) {
            return AppPackages.of(List.of());
        }
        AppPackages packages = config.packages().isEmpty() ? AppPackages.detect() : AppPackages.of(config.packages());
        LOGGER.log(System.Logger.Level.INFO, packages.isEmpty()
                ? "QueryScope could not tell the application's package, so the call tree is off."
                + " Pass package=com.acme to turn it on."
                : "QueryScope records the call tree of " + String.join(", ", packages.names()));
        return packages;
    }

    private static void startPanel(AgentConfig config, ReportStore store, AppPackages profiled) {
        try {
            PanelServer panel = PanelServer.start(config.port(), store, profiled.names());
            String url = "http://localhost:" + panel.port();
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
