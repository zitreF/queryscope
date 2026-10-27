package me.fertiz.queryscope;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.UnitKind;
import me.fertiz.queryscope.agent.instrument.AgentInstaller;
import me.fertiz.queryscope.agent.trace.RequestTracer;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInstrumentationTest {

    private static final List<RequestTrace> TRACES = new CopyOnWriteArrayList<>();
    private static final String BY_ID = "select title from books where id = ?";

    private static HikariDataSource dataSource;

    @BeforeAll
    static void installAgent() throws SQLException {
        AgentInstaller.install(ByteBuddyAgent.install(), List.of(ImportJob.class.getName() + "#run"));
        RequestTracer.publishTo(TRACES::add);

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

        RequestTrace trace = singleTrace();
        assertEquals(UnitKind.HTTP, trace.kind());
        assertEquals("GET", trace.method());
        assertEquals("/books", trace.path());
        assertEquals(200, trace.status());
        assertEquals(List.of(BY_ID, BY_ID, BY_ID), trace.queries().stream().map(QueryExecution::sql).toList());
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

        RequestTrace trace = singleTrace();
        assertEquals("/outer", trace.path());
        assertEquals(2, trace.queries().size());
    }

    @Test
    void tracesJavaxServletRequestsToo() throws Exception {
        new JavaxWorkChain(AgentInstrumentationTest::findTitle).doFilter(javaxRequest("/legacy"), javaxResponse());

        RequestTrace trace = singleTrace();
        assertEquals(UnitKind.HTTP, trace.kind());
        assertEquals("/legacy", trace.path());
        assertEquals(201, trace.status());
        assertEquals(1, trace.queries().size());
    }

    @Test
    void groupsQueriesOutsideRequestsIntoOneTaskPerConnection() throws SQLException {
        findTitle();
        findTitle();

        assertEquals(2, TRACES.size());
        for (RequestTrace trace : TRACES) {
            assertEquals(UnitKind.TASK, trace.kind());
            assertEquals("AgentInstrumentationTest.findTitle", trace.path());
            assertEquals(1, trace.queries().size());
        }
    }

    @Test
    void entryMethodGroupsAllItsQueriesIntoOneTask() throws SQLException {
        new ImportJob(dataSource).run();

        RequestTrace trace = singleTrace();
        assertEquals(UnitKind.TASK, trace.kind());
        assertEquals("ImportJob.run", trace.path());
        assertEquals(2, trace.queries().size());
    }

    @Test
    void requestClosesBackgroundTaskLeftOpenOnItsThread() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery("select count(*) from books").close();
            handle(request(DispatcherType.REQUEST, "/books"), AgentInstrumentationTest::findTitle);
        }

        assertEquals(List.of(UnitKind.TASK, UnitKind.HTTP), TRACES.stream().map(RequestTrace::kind).toList());
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

    private static void findTitle() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(BY_ID)) {
            statement.setInt(1, 1);
            statement.executeQuery().close();
        }
    }

    private static RequestTrace singleTrace() {
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
        return (HttpServletRequest) Proxy.newProxyInstance(
                AgentInstrumentationTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getMethod" -> "GET";
                    case "getRequestURI" -> uri;
                    case "getQueryString" -> null;
                    case "getDispatcherType" -> dispatcherType;
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
