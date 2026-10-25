package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.analysis.QueryAnalyzer;
import me.fertiz.queryscope.agent.instrument.AgentInstaller;
import me.fertiz.queryscope.agent.report.ConsoleReporter;
import me.fertiz.queryscope.agent.trace.RequestTracer;

import java.lang.instrument.Instrumentation;

public final class QueryScopeAgent {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");

    private QueryScopeAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        AgentConfig config = AgentConfig.parse(arguments);
        QueryAnalyzer analyzer = new QueryAnalyzer(config.nPlusOneThreshold(), config.slowQueryThreshold());
        ConsoleReporter console = new ConsoleReporter();
        RequestTracer.publishTo(trace -> console.accept(analyzer.analyze(trace)));
        AgentInstaller.install(instrumentation);
        LOGGER.log(System.Logger.Level.INFO, "QueryScope is watching JDBC queries per HTTP request");
    }
}
