package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.appcode.CallSites;
import me.fertiz.queryscope.agent.config.AgentConfig;
import me.fertiz.queryscope.agent.config.AgentProperties;
import me.fertiz.queryscope.agent.console.ConsoleReporter;
import me.fertiz.queryscope.agent.console.SessionSummary;
import me.fertiz.queryscope.agent.findings.QueryAnalyzer;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.http.Browser;
import me.fertiz.queryscope.agent.http.LocalServer;
import me.fertiz.queryscope.agent.loadtest.LoadTester;
import me.fertiz.queryscope.agent.methodtrace.MethodTracer;
import me.fertiz.queryscope.agent.panel.PanelServer;
import me.fertiz.queryscope.agent.panel.ReportStore;
import me.fertiz.queryscope.agent.profiler.Profile;
import me.fertiz.queryscope.agent.profiler.SampleStore;
import me.fertiz.queryscope.agent.profiler.Sampling;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitTracer;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QueryScopeAgent {

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
        exitWhenLauncherExits();
        QueryAnalyzer analyzer = new QueryAnalyzer(config.thresholds());
        ConsoleReporter console = new ConsoleReporter();
        ReportStore store = new ReportStore(STORED_REQUESTS);
        SessionSummary summary = new SessionSummary(Clock.systemUTC());
        LoadTester loadTester = new LoadTester();
        UnitTracer.publishTo(trace -> analyzeAndPublish(trace, analyzer, console, store, summary, loadTester));
        AppPackages application = config.packages().isEmpty()
                ? AppPackages.fromMainClass()
                : AppPackages.of(config.packages());
        AppPackages callTreePackages = config.callTree() ? application : AppPackages.of(List.of());
        CallSites.treatAsApplicationCode(config.packages());
        if (config.callTree()) {
            logCallTreePackages(application);
        }
        AgentInstaller.install(instrumentation, config.entryMethods(), callTreePackages);
        hookJdkClasses(instrumentation, config.async());
        MethodTracer tracer = new MethodTracer(instrumentation::getAllLoadedClasses,
                (className, methodName) -> AgentInstaller.installMethodTrace(instrumentation, className, methodName));
        startTraces(tracer, config.traceMethods());
        SampleStore samples = new SampleStore(Clock.systemUTC(), STORED_SAMPLES, SAMPLE_AGE);
        Sampling sampling = config.sampling() ? Sampling.start(samples, config.sampleInterval()) : null;
        startPanel(config, store, callTreePackages, application, samples, tracer, loadTester);
        if (config.printSummary()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (sampling != null) {
                    sampling.stop();
                }
                summary.print(System.out, Profile.build(samples, application, Profile.Filter.allSamples()),
                        UnitTracer.openTaskCount());
            }, "queryscope-summary"));
        }
    }

    private static void analyzeAndPublish(UnitTrace trace, QueryAnalyzer analyzer, ConsoleReporter console,
                                          ReportStore store, SessionSummary summary, LoadTester loadTester) {
        if (loadTester.sent(trace)) {
            loadTester.record(analyzer.analyze(trace));
            return;
        }
        boolean ranSql = !trace.queries().isEmpty();
        if (!ranSql && trace.callTree().topLevelCalls().isEmpty()) {
            return;
        }
        UnitReport report = analyzer.analyze(trace);
        store.add(report);
        summary.accept(report);
        if (ranSql || !report.findings().isEmpty()) {
            console.log(report);
        }
    }

    private static void logCallTreePackages(AppPackages application) {
        LOGGER.log(System.Logger.Level.INFO, application.isEmpty()
                ? "QueryScope could not tell the application's package, so the call tree is off."
                + " Pass package=com.acme to turn it on."
                : "QueryScope records the call tree of " + String.join(", ", application.names()));
    }

    private static void exitWhenLauncherExits() {
        String launcherPid = System.getProperty(AgentProperties.LAUNCHER_PID);
        if (launcherPid != null) {
            ProcessHandle.of(Long.parseLong(launcherPid))
                    .ifPresent(launcher -> launcher.onExit().thenRun(() -> System.exit(0)));
        }
    }

    private static void hookJdkClasses(Instrumentation instrumentation, boolean async) {
        try {
            AgentInstaller.installJdkHooks(instrumentation, async);
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.log(System.Logger.Level.WARNING, "QueryScope cannot see requests of the JDK's HttpServer"
                    + (async ? " or follow requests into their async tasks: " : ": ") + e);
        }
    }

    private static void startTraces(MethodTracer tracer, List<String> methods) {
        for (String method : methods) {
            try {
                tracer.start(method);
                LOGGER.log(System.Logger.Level.INFO, "QueryScope traces the calls of " + method);
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "QueryScope cannot trace " + method + ": " + e.getMessage());
            }
        }
    }

    private static void startPanel(AgentConfig config, ReportStore store, AppPackages callTreePackages,
                                   AppPackages application, SampleStore samples, MethodTracer tracer,
                                   LoadTester loadTester) {
        try {
            LocalServer panel = PanelServer.start(config.port(), config.portGiven(), store, callTreePackages,
                    application, samples, tracer, loadTester);
            String url = "http://localhost:" + panel.port();
            System.setProperty(AgentProperties.PANEL_PORT, String.valueOf(panel.port()));
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
