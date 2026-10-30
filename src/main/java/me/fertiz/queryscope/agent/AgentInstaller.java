package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.appcode.CallSites;
import me.fertiz.queryscope.agent.calltree.CallTracer;
import me.fertiz.queryscope.agent.calltree.MethodAdvice;
import me.fertiz.queryscope.agent.trace.ClearParametersAdvice;
import me.fertiz.queryscope.agent.trace.ConnectionReleaseAdvice;
import me.fertiz.queryscope.agent.trace.EntryAdvice;
import me.fertiz.queryscope.agent.trace.ExecutePreparedAdvice;
import me.fertiz.queryscope.agent.trace.ExecuteSqlAdvice;
import me.fertiz.queryscope.agent.trace.ParameterAdvice;
import me.fertiz.queryscope.agent.trace.PrepareStatementAdvice;
import me.fertiz.queryscope.agent.trace.RowAdvice;
import me.fertiz.queryscope.agent.trace.ServletAdvice;
import me.fertiz.queryscope.agent.trace.Servlets;
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

    public static void install(Instrumentation instrumentation, List<String> entryMethods, AppPackages callTreePackages) {
        System.setProperty(Nexus.PROPERTY, "true");
        if (!callTreePackages.isEmpty()) {
            CallTracer.enable();
        }
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new ErrorLogger())
                .ignore(nameStartsWith(CallSites.AGENT_PACKAGE_PREFIX)
                        .or(nameStartsWith("net.bytebuddy."))
                        .or(nameStartsWith("java."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("com.sun."))
                        .or(isSynthetic()));
        List<Hook> hooks = new ArrayList<>(servletHooks());
        hooks.addAll(jdbcHooks());
        hooks.addAll(entryHooks(entryMethods));
        if (!callTreePackages.isEmpty()) {
            hooks.add(callTreeHook(callTreePackages));
        }
        for (Hook hook : hooks) {
            builder = builder.type(hook.types()).transform(new AgentBuilder.Transformer.ForAdvice()
                    .include(AgentInstaller.class.getClassLoader())
                    .advice(not(isAbstract()).and(hook.methods()), hook.advice().getName()));
        }
        builder.installOn(instrumentation);
    }

    private static List<Hook> servletHooks() {
        return List.of(
                new Hook(concreteTypesImplementing(namedOneOf(Servlets.inBothServletNamespaces("FilterChain"))),
                        named("doFilter").and(takesArguments(2)),
                        ServletAdvice.class),
                new Hook(namedOneOf(Servlets.inBothServletNamespaces("http.HttpServlet")),
                        named("service").and(takesArguments(2))
                                .and(takesArgument(0, namedOneOf(Servlets.inBothServletNamespaces("ServletRequest")))),
                        ServletAdvice.class));
    }

    private static List<Hook> jdbcHooks() {
        return List.of(
                new Hook(concreteTypesImplementing(is(Connection.class)),
                        namedOneOf("prepareStatement", "prepareCall").and(takesArgument(0, String.class)),
                        PrepareStatementAdvice.class),
                new Hook(concreteTypesImplementing(is(Connection.class)),
                        namedOneOf(RELEASE_METHODS).and(takesArguments(0)),
                        ConnectionReleaseAdvice.class),
                new Hook(concreteTypesImplementing(is(Statement.class)),
                        namedOneOf(EXECUTE_METHODS).and(takesArgument(0, String.class)),
                        ExecuteSqlAdvice.class),
                new Hook(concreteTypesImplementing(is(Statement.class)),
                        namedOneOf(EXECUTE_METHODS).or(namedOneOf(BATCH_METHODS)).and(takesArguments(0)),
                        ExecutePreparedAdvice.class),
                new Hook(concreteTypesImplementing(is(PreparedStatement.class)),
                        nameStartsWith("set").and(takesArgument(0, int.class))
                                .and(takesArguments(2).or(takesArguments(3)))
                                .and(isOverriddenFrom(PreparedStatement.class)),
                        ParameterAdvice.class),
                new Hook(concreteTypesImplementing(is(PreparedStatement.class)),
                        named("clearParameters").and(takesArguments(0)),
                        ClearParametersAdvice.class),
                new Hook(concreteTypesImplementing(is(ResultSet.class)),
                        named("next").and(takesArguments(0)),
                        RowAdvice.class));
    }

    private static List<Hook> entryHooks(List<String> entryMethods) {
        return entryMethods.stream()
                .map(entryMethod -> entryMethod.split("#", 2))
                .map(typeAndMethod -> new Hook(named(typeAndMethod[0]), named(typeAndMethod[1]), EntryAdvice.class))
                .toList();
    }

    private static Hook callTreeHook(AppPackages callTreePackages) {
        ElementMatcher<TypeDescription> application = type -> callTreePackages.includesClass(type.getName());
        return new Hook(not(isInterface()).and(application),
                isMethod().and(not(isNative())).and(not(isSynthetic())).and(not(isBridge())),
                MethodAdvice.class);
    }

    private static ElementMatcher.Junction<TypeDescription> concreteTypesImplementing(
            ElementMatcher<? super TypeDescription> api) {
        return not(isInterface()).and(hasSuperType(api));
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
