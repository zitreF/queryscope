package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.appcode.CallSites;
import me.fertiz.queryscope.agent.bootstrap.HttpServerBridge;
import me.fertiz.queryscope.agent.calltree.CallTracer;
import me.fertiz.queryscope.agent.calltree.MethodAdvice;
import me.fertiz.queryscope.agent.methodtrace.MethodTraceAdvice;
import me.fertiz.queryscope.agent.trace.AsyncForkAdvice;
import me.fertiz.queryscope.agent.trace.AsyncRunAdvice;
import me.fertiz.queryscope.agent.trace.AsyncSubmitAdvice;
import me.fertiz.queryscope.agent.trace.AsyncTasks;
import me.fertiz.queryscope.agent.trace.ClearParametersAdvice;
import me.fertiz.queryscope.agent.trace.ConnectionReleaseAdvice;
import me.fertiz.queryscope.agent.trace.EntryAdvice;
import me.fertiz.queryscope.agent.trace.ExecutePreparedAdvice;
import me.fertiz.queryscope.agent.trace.ExecuteSqlAdvice;
import me.fertiz.queryscope.agent.trace.JdkHttpServerAdvice;
import me.fertiz.queryscope.agent.trace.JdkHttpServerRequests;
import me.fertiz.queryscope.agent.trace.ParameterAdvice;
import me.fertiz.queryscope.agent.trace.PrepareStatementAdvice;
import me.fertiz.queryscope.agent.trace.RowAdvice;
import me.fertiz.queryscope.agent.trace.ServletAdvice;
import me.fertiz.queryscope.agent.trace.Servlets;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.Nexus;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class AgentInstaller {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final String[] EXECUTE_METHODS = {"execute", "executeQuery", "executeUpdate", "executeLargeUpdate"};
    private static final String[] BATCH_METHODS = {"executeBatch", "executeLargeBatch"};
    private static final String[] RELEASE_METHODS = {"close", "commit", "rollback"};
    private static final String HTTP_SERVER_BRIDGE = "me.fertiz.queryscope.agent.bootstrap.HttpServerBridge";
    private static final String HTTP_SERVER_FILTER_CHAIN = "com.sun.net.httpserver.Filter$Chain";
    private static final String ASYNC_BRIDGE = "me.fertiz.queryscope.agent.bootstrap.AsyncBridge";
    private static final Set<String> ASYNC_BRIDGE_CLASSES = Set.of(ASYNC_BRIDGE, ASYNC_BRIDGE + "$Hooks",
            ASYNC_BRIDGE + "$TrackedRunnable", ASYNC_BRIDGE + "$TrackedCallable");
    private static final String CONCURRENT = "java.util.concurrent.";

    private AgentInstaller() {
    }

    public static void install(Instrumentation instrumentation, List<String> entryMethods, AppPackages callTreePackages) {
        System.setProperty(Nexus.PROPERTY, "true");
        if (!callTreePackages.isEmpty()) {
            CallTracer.enable();
        }
        AgentBuilder builder = retransformingBuilder();
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

    public static Runnable installMethodTrace(Instrumentation instrumentation, String className, String methodName) {
        ResettableClassFileTransformer transformer = retransformingBuilder()
                .type(named(className))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        .include(AgentInstaller.class.getClassLoader())
                        .advice(named(methodName).and(not(isAbstract())).and(not(isNative())),
                                MethodTraceAdvice.class.getName()))
                .installOn(instrumentation);
        return () -> transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    public static void installJdkHttpServerHook(Instrumentation instrumentation) throws IOException {
        Class<?> bridge = injectIntoBootstrap(instrumentation, Set.of(HTTP_SERVER_BRIDGE)).get(HTTP_SERVER_BRIDGE);
        HttpServerBridge.connect(JdkHttpServerRequests::requestStarted, JdkHttpServerRequests::requestFinished);
        installOnJdkClasses(instrumentation, bridge, List.of(new Hook(named(HTTP_SERVER_FILTER_CHAIN),
                named("doFilter").and(takesArguments(1)), JdkHttpServerAdvice.class)));
    }

    public static void installAsyncHooks(Instrumentation instrumentation) throws IOException {
        Class<?> bridge = injectIntoBootstrap(instrumentation, ASYNC_BRIDGE_CLASSES).get(ASYNC_BRIDGE);
        AsyncTasks.connect();
        installOnJdkClasses(instrumentation, bridge, List.of(
                new Hook(named(CONCURRENT + "ThreadPoolExecutor"),
                        named("execute").and(takesArguments(Runnable.class)),
                        AsyncSubmitAdvice.class),
                new Hook(named(CONCURRENT + "ForkJoinPool"),
                        namedOneOf("execute", "submit", "invoke").and(takesArguments(1)).and(not(isBridge())),
                        AsyncSubmitAdvice.class),
                new Hook(named(CONCURRENT + "ForkJoinTask"),
                        named("fork").and(takesArguments(0)),
                        AsyncForkAdvice.class),
                new Hook(named(CONCURRENT + "ForkJoinTask"),
                        named("doExec").and(takesArguments(0)),
                        AsyncRunAdvice.class),
                new Hook(named(CONCURRENT + "FutureTask"),
                        named("run").and(takesArguments(0)),
                        AsyncRunAdvice.class),
                new Hook(namedOneOf(CONCURRENT + "CompletableFuture$AsyncSupply", CONCURRENT + "CompletableFuture$AsyncRun",
                        CONCURRENT + "CompletableFuture$Completion"),
                        named("run").and(takesArguments(0)),
                        AsyncRunAdvice.class)));
    }

    private static Map<String, Class<?>> injectIntoBootstrap(Instrumentation instrumentation, Set<String> classes)
            throws IOException {
        Path folder = Files.createTempDirectory("queryscope-bootstrap");
        ClassFileLocator agentClasses = ClassFileLocator.ForClassLoader.of(AgentInstaller.class.getClassLoader());
        Map<String, Class<?>> injected = ClassInjector.UsingInstrumentation
                .of(folder.toFile(), ClassInjector.UsingInstrumentation.Target.BOOTSTRAP, instrumentation)
                .injectRaw(classes, agentClasses);
        deleteOnExit(folder);
        return injected;
    }

    private static void installOnJdkClasses(Instrumentation instrumentation, Class<?> bridge, List<Hook> hooks) {
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new ErrorLogger())
                .ignore(none())
                .assureReadEdgeTo(instrumentation, bridge);
        for (Hook hook : hooks) {
            builder = builder.type(hook.types()).transform(new AgentBuilder.Transformer.ForAdvice()
                    .include(AgentInstaller.class.getClassLoader())
                    .advice(not(isAbstract()).and(hook.methods()), hook.advice().getName()));
        }
        builder.installOn(instrumentation);
    }

    private static void deleteOnExit(Path folder) throws IOException {
        folder.toFile().deleteOnExit();
        try (Stream<Path> files = Files.list(folder)) {
            files.forEach(file -> file.toFile().deleteOnExit());
        }
    }

    private static AgentBuilder retransformingBuilder() {
        return new AgentBuilder.Default()
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
