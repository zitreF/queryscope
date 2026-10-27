package me.fertiz.queryscope.agent.instrument;

import me.fertiz.queryscope.agent.trace.AppPackages;
import me.fertiz.queryscope.agent.trace.CallTracer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.Nexus;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isAnnotation;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isOverriddenFrom;
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
    private static final String[] RELEASE_METHODS = {"close", "commit", "rollback"};

    private AgentInstaller() {
    }

    public static void install(Instrumentation instrumentation, List<String> entryMethods, AppPackages profiled) {
        disableNexusInjection();
        if (!profiled.isEmpty()) {
            CallTracer.enable();
        }
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new ErrorLogger())
                .ignore(nameStartsWith("me.fertiz.queryscope.agent.")
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("java."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun."))
                        .or(isSynthetic()));
        for (Hook hook : hooks(entryMethods, profiled)) {
            builder = builder.type(hook.types()).transform(new AgentBuilder.Transformer.ForAdvice()
                    .include(AgentInstaller.class.getClassLoader())
                    .advice(not(isAbstract()).and(hook.methods()), hook.advice().getName()));
        }
        builder.installOn(instrumentation);
    }

    private static List<Hook> hooks(List<String> entryMethods, AppPackages profiled) {
        List<Hook> hooks = new ArrayList<>(List.of(
                new Hook(implementing(Servlets.typeNames("FilterChain")),
                        named("doFilter").and(takesArguments(2)),
                        ServletAdvice.class),
                new Hook(namedOneOf(Servlets.typeNames("http.HttpServlet").toArray(String[]::new)),
                        named("service").and(takesArguments(2)).and(takesArgument(0,
                                namedOneOf(Servlets.typeNames("ServletRequest").toArray(String[]::new)))),
                        ServletAdvice.class),
                new Hook(implementing(Connection.class),
                        namedOneOf("prepareStatement", "prepareCall").and(takesArgument(0, String.class)),
                        PrepareStatementAdvice.class),
                new Hook(implementing(Connection.class),
                        namedOneOf(RELEASE_METHODS).and(takesArguments(0)),
                        ConnectionReleaseAdvice.class),
                new Hook(implementing(Statement.class),
                        namedOneOf(EXECUTE_METHODS).and(takesArgument(0, String.class)),
                        ExecuteSqlAdvice.class),
                new Hook(implementing(Statement.class),
                        namedOneOf(EXECUTE_METHODS).or(namedOneOf(BATCH_METHODS)).and(takesArguments(0)),
                        ExecutePreparedAdvice.class),
                new Hook(implementing(PreparedStatement.class),
                        nameStartsWith("set").and(takesArgument(0, int.class))
                                .and(takesArguments(2).or(takesArguments(3)))
                                .and(isOverriddenFrom(PreparedStatement.class)),
                        ParameterAdvice.class),
                new Hook(implementing(PreparedStatement.class),
                        named("clearParameters").and(takesArguments(0)),
                        ClearParametersAdvice.class),
                new Hook(implementing(ResultSet.class),
                        named("next").and(takesArguments(0)),
                        RowAdvice.class)));
        for (String entryMethod : entryMethods) {
            String[] typeAndMethod = entryMethod.split("#", 2);
            hooks.add(new Hook(named(typeAndMethod[0]), named(typeAndMethod[1]), EntryAdvice.class));
        }
        if (!profiled.isEmpty()) {
            ElementMatcher<TypeDescription> application = type -> profiled.covers(type.getName());
            hooks.add(new Hook(not(isInterface()).and(not(isAnnotation())).and(application),
                    isMethod().and(not(isNative())).and(not(isSynthetic())).and(not(isBridge())),
                    MethodAdvice.class));
        }
        return hooks;
    }

    private static void disableNexusInjection() {
        System.setProperty(Nexus.PROPERTY, "true");
    }

    private static ElementMatcher.Junction<TypeDescription> implementing(Class<?> api) {
        return not(isInterface()).and(hasSuperType(is(api)));
    }

    private static ElementMatcher.Junction<TypeDescription> implementing(List<String> apiNames) {
        return not(isInterface()).and(hasSuperType(namedOneOf(apiNames.toArray(String[]::new))));
    }

    private record Hook(
            ElementMatcher<? super TypeDescription> types,
            ElementMatcher<? super MethodDescription> methods,
            Class<?> advice
    ) {
    }

    private static final class ErrorLogger extends AgentBuilder.Listener.Adapter {

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
                            Throwable throwable) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not instrument " + typeName + ": " + throwable);
        }
    }
}
