package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.analysis.QueryAnalyzer;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.instrument.AgentInstaller;
import me.fertiz.queryscope.agent.panel.PanelServer;
import me.fertiz.queryscope.agent.panel.ReportStore;
import me.fertiz.queryscope.agent.report.ConsoleReporter;
import me.fertiz.queryscope.agent.trace.RequestTracer;

import java.io.IOException;
import java.lang.instrument.Instrumentation;

public final class QueryScopeAgent {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final int STORED_REQUESTS = 200;

    private QueryScopeAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        AgentConfig config = AgentConfig.parse(arguments);
        QueryAnalyzer analyzer = new QueryAnalyzer(config.nPlusOneThreshold(), config.slowQueryThreshold());
        ConsoleReporter console = new ConsoleReporter();
        ReportStore store = new ReportStore(STORED_REQUESTS);
        RequestTracer.publishTo(trace -> {
            if (!trace.queries().isEmpty()) {
                RequestReport report = analyzer.analyze(trace);
                console.accept(report);
                store.add(report);
            }
        });
        AgentInstaller.install(instrumentation, config.entryMethods());
        startPanel(config.port(), store);
    }

    private static void startPanel(int port, ReportStore store) {
        try {
            PanelServer panel = PanelServer.start(port, store);
            LOGGER.log(System.Logger.Level.INFO, "QueryScope panel is running at http://localhost:" + panel.port());
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "QueryScope panel could not start on port " + port + ", queries are logged only: " + e);
        }
    }
}
