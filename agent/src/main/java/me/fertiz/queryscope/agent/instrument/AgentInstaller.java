package me.fertiz.queryscope.agent.instrument;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.Nexus;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class AgentInstaller {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final String[] EXECUTE_METHODS = {"execute", "executeQuery", "executeUpdate", "executeLargeUpdate"};
    private static final String[] BATCH_METHODS = {"executeBatch", "executeLargeBatch"};

    private AgentInstaller() {
    }

    public static void install(Instrumentation instrumentation) {
        disableNexusInjection();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new ErrorLogger())
                .ignore(nameStartsWith("me.fertiz.queryscope.agent.")
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("java."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun."))
                        .or(isSynthetic()))
                .type(implementing("jakarta.servlet.FilterChain"))
                .transform(advice()
                        .advice(concrete("doFilter").and(takesArguments(2)), adviceClass("ServletAdvice")))
                .type(named("jakarta.servlet.http.HttpServlet"))
                .transform(advice()
                        .advice(concrete("service").and(takesArgument(0, named("jakarta.servlet.ServletRequest"))),
                                adviceClass("ServletAdvice")))
                .type(implementing("java.sql.Connection"))
                .transform(advice()
                        .advice(concrete("prepareStatement", "prepareCall").and(takesArgument(0, String.class)),
                                adviceClass("PrepareStatementAdvice")))
                .type(implementing("java.sql.Statement"))
                .transform(advice()
                        .advice(concrete(EXECUTE_METHODS).and(takesArgument(0, String.class)),
                                adviceClass("ExecuteSqlAdvice"))
                        .advice(concrete(EXECUTE_METHODS).or(concrete(BATCH_METHODS)).and(takesArguments(0)),
                                adviceClass("ExecutePreparedAdvice")))
                .installOn(instrumentation);
    }

    private static void disableNexusInjection() {
        System.setProperty(Nexus.PROPERTY, "true");
    }

    private static ElementMatcher.Junction<TypeDescription> implementing(String interfaceName) {
        return not(isInterface()).and(hasSuperType(named(interfaceName)));
    }

    private static ElementMatcher.Junction<MethodDescription> concrete(String... names) {
        return namedOneOf(names).and(not(isAbstract()));
    }

    private static AgentBuilder.Transformer.ForAdvice advice() {
        return new AgentBuilder.Transformer.ForAdvice().include(AgentInstaller.class.getClassLoader());
    }

    private static String adviceClass(String simpleName) {
        return AgentInstaller.class.getPackageName() + "." + simpleName;
    }

    private static final class ErrorLogger extends AgentBuilder.Listener.Adapter {

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
                            Throwable throwable) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not instrument " + typeName + ": " + throwable);
        }
    }
}
