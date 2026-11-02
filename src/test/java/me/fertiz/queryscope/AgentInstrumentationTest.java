package me.fertiz.queryscope;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.fertiz.queryscope.agent.config.AgentConfig;
import me.fertiz.queryscope.agent.AgentInstaller;
import me.fertiz.queryscope.agent.appcode.AppPackages;
import me.fertiz.queryscope.agent.calltree.CallNode;
import me.fertiz.queryscope.agent.calltree.CallTree;
import me.fertiz.queryscope.agent.findings.QueryAnalyzer;
import me.fertiz.queryscope.agent.findings.TooManyQueries;
import me.fertiz.queryscope.agent.http.HttpSupport;
import me.fertiz.queryscope.agent.http.LocalServer;
import me.fertiz.queryscope.agent.loadtest.LoadTestPlan;
import me.fertiz.queryscope.agent.loadtest.LoadTester;
import me.fertiz.queryscope.agent.methodtrace.MethodTracer;
import me.fertiz.queryscope.agent.methodtrace.TracedMethod;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitTracer;
import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.profiled.BookCatalog;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInstrumentationTest {

    private static final List<UnitTrace> TRACES = new CopyOnWriteArrayList<>();
    private static final String BY_ID = "select title from books where id = ?";
    private static final long PAUSE_MILLIS = 200;
    private static final int APP_PORT = 8080;

    private static HikariDataSource dataSource;
    private static Instrumentation instrumentation;

    @BeforeAll
    static void installAgent() throws SQLException, IOException {
        instrumentation = ByteBuddyAgent.install();
        AgentInstaller.install(instrumentation, List.of(ImportJob.class.getName() + "#run"),
                AppPackages.of(List.of(BookCatalog.class.getPackageName())));
        AgentInstaller.installJdkHttpServerHook(instrumentation);
        AgentInstaller.installAsyncHooks(instrumentation);
        UnitTracer.publishTo(TRACES::add);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:agent-test;DB_CLOSE_DELAY=-1");
        dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table books (id int primary key, title varchar(100))");
            statement.execute("insert into books values (1, 'Dune'), (2, 'Solaris'), (3, 'Hyperion')");
        }
    }

    @AfterAll
    static void closePool() {
        dataSource.close();
    }

    @BeforeEach
    void clearTraces() {
        TRACES.clear();
    }

    @Test
    void recordsEachPreparedQueryOnceThroughPoolProxies() {
        handle(request(DispatcherType.REQUEST, "/books"), () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(BY_ID)) {
                for (int id = 1; id <= 3; id++) {
                    statement.setInt(1, id);
                    statement.executeQuery().close();
                }
            }
        });

        UnitTrace trace = singleTrace();
        assertEquals(UnitKind.HTTP, trace.kind());
        assertEquals("GET", trace.method());
        assertEquals("/books", trace.path());
        assertEquals(200, trace.status());
        assertEquals(APP_PORT, trace.localPort());
        assertEquals(UnitTracer.NO_LOAD_TEST, trace.loadTestRun());
        assertEquals(List.of(BY_ID, BY_ID, BY_ID), trace.queries().stream().map(QueryExecution::sql).toList());
    }

    @Test
    void marksRequestsSentByALoadTest() {
        handle(request(DispatcherType.REQUEST, "/books", "7"), AgentInstrumentationTest::findTitle);

        assertEquals(7, singleTrace().loadTestRun());
    }

    @Test
    void ignoresLoadTestHeadersThatAreNoRunNumber() {
        handle(request(DispatcherType.REQUEST, "/books", "soon"), AgentInstrumentationTest::findTitle);

        assertEquals(UnitTracer.NO_LOAD_TEST, singleTrace().loadTestRun());
    }

    @Test
    void capturesBoundParametersAndCountsRowsOnceThroughPoolProxies() {
        handle(request(DispatcherType.REQUEST, "/books"), () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "select title from books where id > ? and title <> ?")) {
                statement.setInt(1, 0);
                statement.setString(2, "Missing");
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        rows.getString(1);
                    }
                }
            }
        });

        QueryExecution query = singleTrace().queries().get(0);
        assertEquals("[0, 'Missing']", query.parameters());
        assertEquals(3, query.rows());
    }

    @Test
    void leavesRowsUnknownForStatementsWithoutResult() {
        handle(request(DispatcherType.REQUEST, "/books"), () -> {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("update books set title = title where id = 1");
            }
        });

        assertEquals(QueryExecution.NO_RESULT, singleTrace().queries().get(0).rows());
    }

    @Test
    void recordsPlainStatementsAndMarksFailedOnes() {
        handle(request(DispatcherType.REQUEST, "/books"), () -> {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeQuery("select count(*) from books").close();
                assertThrows(SQLException.class, () -> statement.executeQuery("select * from missing_table"));
            }
        });

        List<QueryExecution> queries = singleTrace().queries();
        assertEquals(2, queries.size());
        assertFalse(queries.get(0).failed());
        assertTrue(queries.get(1).failed());
        assertEquals("select * from missing_table", queries.get(1).sql());
    }

    @Test
    void forwardedDispatchBelongsToOriginalRequest() {
        handle(request(DispatcherType.REQUEST, "/outer"), () -> {
            handle(request(DispatcherType.FORWARD, "/inner"), AgentInstrumentationTest::findTitle);
            findTitle();
        });

        UnitTrace trace = singleTrace();
        assertEquals("/outer", trace.path());
        assertEquals(2, trace.queries().size());
    }

    @Test
    void countsTheQueriesOfTasksARequestSubmitsToAnExecutor() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            handle(request(DispatcherType.REQUEST, "/books"), () -> {
                findTitle();
                pool.submit(AgentInstrumentationTest::findTitleQuietly).get();
                pool.invokeAll(List.of(() -> findTitleQuietly(), () -> findTitleQuietly()));
            });
        } finally {
            pool.shutdownNow();
        }

        UnitTrace trace = awaitSingleTrace();
        assertEquals(UnitKind.HTTP, trace.kind());
        assertEquals(4, trace.queries().size());
        assertTrue(trace.queries().stream().allMatch(query -> query.offsetNanos() < trace.durationNanos()),
                trace.toString());
    }

    @Test
    void countsTheQueriesOfCompletableFuturesAndParallelStreams() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            handle(request(DispatcherType.REQUEST, "/books"), () -> {
                CompletableFuture.supplyAsync(AgentInstrumentationTest::findTitleQuietly, pool)
                        .thenApplyAsync(title -> findTitleQuietly(), pool)
                        .join();
                pool.submit(() -> IntStream.range(0, 8).parallel().forEach(index -> findTitleQuietly())).get();
            });
        } finally {
            pool.shutdownNow();
        }

        UnitTrace trace = awaitSingleTrace();
        assertEquals(10, trace.queries().size());
        assertTrue(trace.callTree().topLevelCalls().stream()
                .anyMatch(node -> node.method().equals(CallTree.ASYNC_TASKS) && node.queryIndexes().size() == 10),
                trace.callTree().toString());
    }

    @Test
    void countsTheQueriesOfPlainRunnablesOnAnExecutor() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        handle(request(DispatcherType.REQUEST, "/books"), () -> {
            pool.execute(AgentInstrumentationTest::findTitleQuietly);
            pool.execute(AgentInstrumentationTest::findTitleQuietly);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        });

        assertEquals(2, awaitSingleTrace().queries().size());
    }

    @Test
    void countsATaskSubmittedTwiceOnlyOnce() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        FutureTask<String> lookup = new FutureTask<>(AgentInstrumentationTest::findTitleQuietly);
        handle(request(DispatcherType.REQUEST, "/books"), () -> {
            pool.execute(lookup);
            pool.execute(lookup);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        });

        assertEquals(1, singleTrace().queries().size());
    }

    @Test
    void waitsBrieflyForTasksStillRunningWhenTheRequestEnds() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch requestDone = new CountDownLatch(1);
        try {
            handle(request(DispatcherType.REQUEST, "/books"), () -> pool.submit(() -> {
                requestDone.await(10, TimeUnit.SECONDS);
                return findTitleQuietly();
            }));
            assertTrue(TRACES.isEmpty(), TRACES.toString());
            requestDone.countDown();

            assertEquals(1, awaitSingleTrace().queries().size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void keepsTasksThatOutliveTheWaitAsTasksOfTheirOwn() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch requestDone = new CountDownLatch(1);
        try {
            handle(request(DispatcherType.REQUEST, "/books"), () -> pool.submit(() -> {
                requestDone.await(10, TimeUnit.SECONDS);
                return findTitleQuietly();
            }));
            UnitTrace request = awaitTrace("/books");
            requestDone.countDown();

            UnitTrace task = awaitTrace("Async from GET /books");
            assertEquals(UnitKind.HTTP, request.kind());
            assertTrue(request.queries().isEmpty());
            assertEquals(UnitKind.TASK, task.kind());
            assertEquals(1, task.queries().size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void leavesExecutorsOutsideRequestsAlone() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(AgentInstrumentationTest::findTitleQuietly).get();
        } finally {
            pool.shutdownNow();
        }

        UnitTrace trace = singleTrace();
        assertEquals(UnitKind.TASK, trace.kind());
        assertFalse(trace.path().startsWith("Async from"), trace.path());
    }

    @Test
    void tracesJavaxServletRequestsToo() throws Exception {
        new JavaxWorkChain(AgentInstrumentationTest::findTitle).doFilter(javaxRequest("/legacy"), javaxResponse());

        UnitTrace trace = singleTrace();
        assertEquals(UnitKind.HTTP, trace.kind());
        assertEquals("/legacy", trace.path());
        assertEquals(201, trace.status());
        assertEquals(UnitTracer.UNKNOWN_PORT, trace.localPort());
        assertEquals(1, trace.queries().size());
    }

    @Test
    void groupsQueriesOutsideRequestsIntoOneTaskPerConnection() throws SQLException {
        findTitle();
        findTitle();

        assertEquals(2, TRACES.size());
        for (UnitTrace trace : TRACES) {
            assertEquals(UnitKind.TASK, trace.kind());
            assertEquals("AgentInstrumentationTest.findTitle", trace.path());
            assertEquals(1, trace.queries().size());
        }
    }

    @Test
    void entryMethodGroupsAllItsQueriesIntoOneTask() throws SQLException {
        new ImportJob(dataSource).run();

        UnitTrace trace = singleTrace();
        assertEquals(UnitKind.TASK, trace.kind());
        assertEquals("ImportJob.run", trace.path());
        assertEquals(2, trace.queries().size());
    }

    @Test
    void requestClosesATaskLeftOpenOnItsThread() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery("select count(*) from books").close();
            handle(request(DispatcherType.REQUEST, "/books"), AgentInstrumentationTest::findTitle);
        }

        assertEquals(List.of(UnitKind.TASK, UnitKind.HTTP), TRACES.stream().map(UnitTrace::kind).toList());
    }

    @Test
    void taskEndsWhenItsConnectionIsReleasedRatherThanAtItsLastQuery() throws Exception {
        new BookCatalog(dataSource).titlesThenPause(PAUSE_MILLIS, 1, 2);

        UnitTrace trace = singleTrace();
        assertEquals(2, trace.queries().size());
        assertTrue(trace.durationNanos() >= Duration.ofMillis(PAUSE_MILLIS).toNanos(), trace.toString());
        assertEveryCallFitsIn(trace);
    }

    @Test
    void taskLeftOpenEndsAfterTheLastMethodItRan() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery("select count(*) from books").close();
            new BookCatalog(dataSource).pause(PAUSE_MILLIS);
            handle(request(DispatcherType.REQUEST, "/books"), AgentInstrumentationTest::findTitle);
        }

        UnitTrace task = TRACES.get(0);
        assertEquals(UnitKind.TASK, task.kind());
        assertTrue(task.durationNanos() >= Duration.ofMillis(PAUSE_MILLIS).toNanos(), task.toString());
        assertEveryCallFitsIn(task);
    }

    @Test
    void recordsTheFirstThousandQueriesAndCountsTheRest() {
        handle(request(DispatcherType.REQUEST, "/import"), () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(BY_ID)) {
                for (int query = 0; query < 1500; query++) {
                    statement.setInt(1, 1);
                    statement.executeQuery().close();
                }
            }
        });

        UnitTrace trace = singleTrace();
        assertEquals(1000, trace.queries().size());
        assertEquals(500, trace.omittedQueries());
        assertEquals(1500, trace.queryCount());
        assertTrue(trace.databaseNanos() > trace.queries().stream().mapToLong(QueryExecution::durationNanos).sum());
        assertTrue(new QueryAnalyzer(AgentConfig.DEFAULTS.thresholds()).analyze(trace).findings().stream()
                .anyMatch(finding -> finding instanceof TooManyQueries tooMany && tooMany.queries() == 1500));
    }

    @Test
    void tracesAMethodAtRuntimeAndKeepsItsCallTree() {
        BookCatalog catalog = new BookCatalog(dataSource);
        MethodTracer tracer = new MethodTracer(instrumentation::getAllLoadedClasses,
                (className, methodName) -> AgentInstaller.installMethodTrace(instrumentation, className, methodName));
        try {
            assertEquals(1, tracer.start(BookCatalog.class.getName() + "#depth").loadedClasses());
            handle(request(DispatcherType.REQUEST, "/catalog"), () -> assertEquals(3, catalog.depth(3)));

            TracedMethod.Snapshot traced = tracer.snapshots().get(0);
            assertEquals(4, traced.calls());
            assertEquals("GET /catalog", traced.recent().get(0).unit());
            assertEquals(4, single(singleTrace().callTree().topLevelCalls()).calls());
        } finally {
            tracer.stop(BookCatalog.class.getName() + ".depth");
        }
        TRACES.clear();

        handle(request(DispatcherType.REQUEST, "/catalog"), () -> assertEquals(2, catalog.depth(2)));

        assertEquals(List.of(), tracer.snapshots());
        assertEquals(3, single(singleTrace().callTree().topLevelCalls()).calls());
    }

    @Test
    void tracesRequestsOfTheJdkHttpServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/books", exchange -> {
            try {
                findTitle();
            } catch (SQLException e) {
                throw new IOException(e);
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            assertEquals(204, send(server.getAddress().getPort(), "/books?id=1"));

            UnitTrace trace = awaitTrace("/books?id=1");
            assertEquals(UnitKind.HTTP, trace.kind());
            assertEquals("GET", trace.method());
            assertEquals(204, trace.status());
            assertEquals(server.getAddress().getPort(), trace.localPort());
            assertEquals(1, trace.queries().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void marksEveryRequestALoadTestSends() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/shelf", exchange -> {
            try {
                findTitle();
            } catch (SQLException e) {
                throw new IOException(e);
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            LoadTester tester = new LoadTester();
            long run = tester.start(new LoadTestPlan("GET /shelf",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/shelf"), 6, 3)).id();

            assertEquals(6, tester.awaitLatest(Duration.ofSeconds(10)).orElseThrow().answered());
            List<UnitTrace> shelf = awaitTraces("/shelf", 6);
            assertTrue(shelf.stream().allMatch(trace -> trace.loadTestRun() == run && trace.queries().size() == 1),
                    shelf.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void leavesTheRequestsOfQueryScopeItselfAlone() throws Exception {
        LocalServer server = LocalServer.start(0, true, "queryscope-test", Map.of("/books", exchange -> {
            try {
                findTitle();
            } catch (SQLException e) {
                throw new IOException(e);
            }
            HttpSupport.sendStatus(exchange, 204);
        }));
        try {
            assertEquals(204, send(server.port(), "/books"));

            assertTrue(TRACES.stream().noneMatch(trace -> trace.kind() == UnitKind.HTTP), TRACES.toString());
        } finally {
            server.stop();
        }
    }

    @Test
    void pointsAtApplicationCodeThatRanTheQuery() {
        handle(request(DispatcherType.REQUEST, "/books"), AgentInstrumentationTest::findTitle);

        String callSite = singleTrace().queries().get(0).callSite();
        assertTrue(callSite.startsWith(AgentInstrumentationTest.class.getName() + ".findTitle("), callSite);
    }

    @Test
    void reportsServerErrorWhenRequestFails() {
        assertThrows(IllegalStateException.class, () -> handle(request(DispatcherType.REQUEST, "/books"), () -> {
            findTitle();
            throw new IllegalStateException("boom");
        }));

        assertEquals(500, singleTrace().status());
    }

    @Test
    void buildsTheCallTreeOfApplicationMethodsWithTheQueriesEachOneRan() {
        handle(request(DispatcherType.REQUEST, "/catalog"), () -> new BookCatalog(dataSource).titles(1, 2, 3));

        CallNode titles = single(singleTrace().callTree().topLevelCalls());
        CallNode title = single(titles.children());
        assertEquals(BookCatalog.class.getName() + ".titles", titles.method());
        assertEquals(1, titles.calls());
        assertFalse(titles.startedBeforeUnit());
        assertEquals(BookCatalog.class.getName() + ".title", title.method());
        assertEquals(3, title.calls());
        assertEquals(List.of(0, 1, 2), title.queryIndexes());
        assertTrue(titles.totalNanos() >= title.totalNanos());
    }

    @Test
    void foldsRecursionAndKeepsTheTreeRightAfterAnException() {
        handle(request(DispatcherType.REQUEST, "/catalog"), () -> {
            BookCatalog catalog = new BookCatalog(dataSource);
            assertEquals(4, catalog.depth(4));
            assertThrows(IllegalArgumentException.class, () -> catalog.reject("123"));
            catalog.titles(1);
        });

        List<CallNode> calls = singleTrace().callTree().topLevelCalls();
        assertEquals(List.of("depth", "reject", "titles"), calls.stream()
                .map(node -> node.method().substring(node.method().lastIndexOf('.') + 1))
                .toList());
        assertEquals(5, calls.get(0).calls());
        assertEquals(List.of(), calls.get(0).children());
        assertEquals(List.of(0), single(calls.get(2).children()).queryIndexes());
    }

    @Test
    void taskStartedInsideApplicationCodeKeepsTheMethodsAlreadyRunning() throws SQLException {
        new BookCatalog(dataSource).titles(1, 2);

        UnitTrace trace = singleTrace();
        CallNode titles = single(trace.callTree().topLevelCalls());
        assertEquals("BookCatalog.titles", trace.path());
        assertTrue(titles.startedBeforeUnit());
        assertEquals(List.of(0, 1), single(titles.children()).queryIndexes());
    }

    private static int send(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static UnitTrace awaitTrace(String path) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            for (UnitTrace trace : TRACES) {
                if (trace.path().equals(path)) {
                    return trace;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No trace of " + path + " in " + TRACES);
    }

    private static List<UnitTrace> awaitTraces(String path, int count) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            List<UnitTrace> found = TRACES.stream().filter(trace -> trace.path().equals(path)).toList();
            if (found.size() >= count) {
                return found;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Fewer than " + count + " traces of " + path + " in " + TRACES);
    }

    private static void assertEveryCallFitsIn(UnitTrace trace) {
        List<CallNode> nodes = new ArrayList<>(trace.callTree().topLevelCalls());
        assertFalse(nodes.isEmpty(), trace.toString());
        while (!nodes.isEmpty()) {
            CallNode node = nodes.remove(nodes.size() - 1);
            assertTrue(node.totalNanos() <= trace.durationNanos(), node + " in " + trace.durationNanos() + " ns");
            nodes.addAll(node.children());
        }
    }

    private static CallNode single(List<CallNode> nodes) {
        assertEquals(1, nodes.size(), nodes.toString());
        return nodes.get(0);
    }

    private static String findTitleQuietly() {
        try {
            findTitle();
            return "found";
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void findTitle() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(BY_ID)) {
            statement.setInt(1, 1);
            statement.executeQuery().close();
        }
    }

    private static UnitTrace awaitSingleTrace() throws InterruptedException {
        for (int attempt = 0; attempt < 100 && TRACES.isEmpty(); attempt++) {
            Thread.sleep(50);
        }
        return singleTrace();
    }

    private static UnitTrace singleTrace() {
        assertEquals(1, TRACES.size(), TRACES.toString());
        return TRACES.get(0);
    }

    private static void handle(HttpServletRequest request, SqlWork work) {
        try {
            new WorkChain(work).doFilter(request, response());
        } catch (ServletException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServletRequest request(DispatcherType dispatcherType, String uri) {
        return request(dispatcherType, uri, null);
    }

    private static HttpServletRequest request(DispatcherType dispatcherType, String uri, String loadTestHeader) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                AgentInstrumentationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getMethod" -> "GET";
                    case "getRequestURI" -> uri;
                    case "getQueryString" -> null;
                    case "getDispatcherType" -> dispatcherType;
                    case "getLocalPort" -> APP_PORT;
                    case "getHeader" -> UnitTracer.LOAD_TEST_HEADER.equals(arguments[0]) ? loadTestHeader : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(
                AgentInstrumentationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getStatus")) {
                        return 200;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static javax.servlet.http.HttpServletRequest javaxRequest(String uri) {
        return (javax.servlet.http.HttpServletRequest) Proxy.newProxyInstance(
                AgentInstrumentationTest.class.getClassLoader(),
                new Class<?>[]{javax.servlet.http.HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getMethod" -> "POST";
                    case "getRequestURI" -> uri;
                    case "getQueryString" -> null;
                    case "getDispatcherType" -> javax.servlet.DispatcherType.REQUEST;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static javax.servlet.http.HttpServletResponse javaxResponse() {
        return (javax.servlet.http.HttpServletResponse) Proxy.newProxyInstance(
                AgentInstrumentationTest.class.getClassLoader(),
                new Class<?>[]{javax.servlet.http.HttpServletResponse.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getStatus")) {
                        return 201;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }

    private static final class WorkChain implements FilterChain {

        private final SqlWork work;

        private WorkChain(SqlWork work) {
            this.work = work;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws ServletException {
            try {
                work.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }
    }

    private static final class JavaxWorkChain implements javax.servlet.FilterChain {

        private final SqlWork work;

        private JavaxWorkChain(SqlWork work) {
            this.work = work;
        }

        @Override
        public void doFilter(javax.servlet.ServletRequest request, javax.servlet.ServletResponse response)
                throws IOException, javax.servlet.ServletException {
            try {
                work.run();
            } catch (Exception e) {
                throw new javax.servlet.ServletException(e);
            }
        }
    }
}
