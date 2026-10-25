# QueryScope

N+1 query detector for Spring Boot. A Java agent built on ByteBuddy that attaches to a Spring Boot app with
`-javaagent`, with no code changes and no extra dependencies in the app. It intercepts JDBC calls, groups the SQL
queries of each HTTP request, detects N+1 patterns and slow queries, and shows a live request timeline in a web panel.

![QueryScope panel showing an N+1 query](docs/panel.png)

```
HTTP request ─> FilterChain.doFilter ─opens─> request trace (per thread)
                                                     ▲
Connection.prepareStatement ─SQL─> StatementSqlRegistry
Statement / PreparedStatement.execute* ─time, SQL, call site─┘
                                                     │ request ends
                                                     ▼
                             QueryAnalyzer + SqlNormalizer ─> N+1 and slow query findings
                                                     │
                              ┌──────────────────────┴──────────────────────┐
                              ▼                                             ▼
                    ConsoleReporter (app log)              ReportStore ─> PanelServer ─SSE─> browser
```

- **AgentInstaller** adds ByteBuddy advice to every class that implements `jakarta.servlet.FilterChain`,
  `java.sql.Connection` or `java.sql.Statement`, plus `HttpServlet.service`. The first `REQUEST` dispatch on a thread
  opens a trace and its exit closes it, so queries run by filters, forwards and the servlet all land in one request.
  The advice only calls static methods that take JDK types, so the agent itself never loads servlet or driver classes
  and works with any JDBC driver and connection pool. Exceptions inside the advice are swallowed and never reach the
  app.
- **RequestTracer** times every query. A call-depth counter makes sure a query is counted once when a pool proxy
  wraps the driver (`HikariProxyPreparedStatement` calling H2's `JdbcPreparedStatement`). `execute()` on a prepared
  statement carries no SQL, so **StatementSqlRegistry** remembers the SQL from `prepareStatement` in a weak identity
  map that never keeps statements alive. `StackWalker` records the first frame outside the JDK, frameworks, drivers
  and generated proxies: the line of application code that ran the query.
- **SqlNormalizer** turns number and string literals into `?`, collapses `IN (1, 2, 3)` to `IN (?)`, and drops
  comments, case and whitespace, so queries that differ only in their values group together.
- **QueryAnalyzer** reports a SELECT that runs at least `nPlusOneThreshold` times in one request as N+1, naming the
  call site behind most of the repeats, and every query that takes at least `slowQueryMs` as slow.
- **PanelServer** is the JDK's built-in `com.sun.net.httpserver`, bound to loopback. It serves a plain HTML/JS panel,
  the last 200 requests that ran SQL as JSON, and a server-sent events stream of new ones. Every SSE client has its
  own queue, so a slow browser never holds up an application thread. Requests with a foreign `Host` header are
  rejected to stop DNS rebinding, and all panel threads are daemons.

ByteBuddy is shaded into `me.fertiz.queryscope.agent.shaded`, so it never clashes with the ByteBuddy that Hibernate
brings. The agent is compiled for Java 17 so it runs on anything from Spring Boot 3 up.

## Stack

Java 25 toolchain (agent targets Java 17: records, sealed interfaces), ByteBuddy 1.18, JDK `HttpServer`, plain
HTML/CSS/JS with server-sent events, JUnit 6. Demo: Spring Boot 4.1, Spring Data JPA, Hibernate 7, H2.
Build: Gradle 9 with Kotlin DSL (wrapper included), Shadow for the agent jar.

## Running the demo

```bash
./gradlew build
java -javaagent:agent/build/libs/queryscope.jar -jar demo-app/build/libs/demo-app.jar
```

On Windows use `gradlew.bat`. Open the panel at http://localhost:7070 and call the demo endpoints:

| Endpoint                                             | What it does                                                         | QueryScope shows               |
|------------------------------------------------------|----------------------------------------------------------------------|--------------------------------|
| http://localhost:8080/api/customers/naive            | `findAll()`, then reads each customer's lazy `orders` collection     | 41 queries, N+1 ×40            |
| http://localhost:8080/api/customers/optimized        | the same summaries with `left join fetch c.orders`                   | 1 query, no findings           |
| http://localhost:8080/api/reports/customer-activity  | joins 150k audit events with customers on the unindexed email column | slow query (about 0.5 s)       |

The N+1 points at `CustomerSummary.of(CustomerSummary.java:12)`, where the stream over the lazy collection runs.
Every request is also summarized in the app's own log:

```
INFO  QueryScope : GET /api/customers/naive -> 200, 41 queries in 46.9 ms
WARN  QueryScope : N+1: 40 x select o1_0.customer_id,o1_0.id,o1_0.placed_at,o1_0.total from orders o1_0 where o1_0.customer_id=? at me.fertiz.queryscope.demo.customer.CustomerSummary.of(CustomerSummary.java:12)
INFO  QueryScope : GET /api/reports/customer-activity -> 200, 1 query in 532.1 ms
WARN  QueryScope : Slow query: 530.5 ms select c.name as customer, count(*) as events from audit_events e join customers c on c.email = e.actor_email group by c.name order by events desc limit ? at me.fertiz.queryscope.demo.report.AuditReportService.mostActiveCustomers(AuditReportService.java:28)
```

![QueryScope panel showing a slow query](docs/panel-slow-query.png)

## Using it with your own app

```bash
java -javaagent:/path/to/queryscope.jar -jar your-app.jar
java -javaagent:/path/to/queryscope.jar=port=9090,nPlusOneThreshold=3,slowQueryMs=250 -jar your-app.jar
```

| Option              | Default | Meaning                                                      |
|---------------------|---------|--------------------------------------------------------------|
| `port`              | `7070`  | Port of the panel on localhost                               |
| `nPlusOneThreshold` | `5`     | How many runs of the same SELECT in one request make an N+1  |
| `slowQueryMs`       | `100`   | Queries taking at least this long are reported as slow       |

An unknown option or a value that is not a positive number stops the JVM at startup with a message that names it.
If the port is taken, the panel is skipped and findings still go to the log.

It is built for servlet apps on `jakarta.servlet` (Spring Boot 3 and 4) running Java 17 or newer, with any JDBC
driver and connection pool. The demo and the tests run Spring Boot 4.1 on Tomcat with HikariCP and H2.

QueryScope is a development tool. It keeps the SQL of the last 200 requests in memory, so don't attach it in
production.

## Limitations

- Work that leaves the request thread is not followed: async servlet requests (`DeferredResult`, `Callable`),
  WebFlux and queries run on other threads.
- `javax.servlet` apps (Spring Boot 2) are not instrumented.
- `Statement.addBatch(sql)` batches are not recorded. Prepared statement batches are.
- The call site skips `org.springframework.*` frames, so apps whose own code lives under that package (such as Spring
  PetClinic) show the nearest frame outside it.
- The panel listens on loopback only, so inside a container it has to be reached from the same network namespace.

## Tests

```bash
./gradlew test
```

- `agent`: unit tests for the normalizer, the analyzer, the options, the JSON writer, the report store and the
  panel server (including SSE and the `Host` check). `AgentInstrumentationTest` installs the agent into the test JVM
  with ByteBuddyAgent and runs real queries through HikariCP and H2 inside simulated requests.
- `demo-app`: the optimized endpoint returns exactly the same summaries as the naive one.
- `e2e`: starts `java -javaagent:queryscope.jar -jar demo-app.jar` as a separate process on free ports and checks
  through the panel API that the naive endpoint is an N+1 at `CustomerSummary.of`, the optimized one runs a single
  query with no findings, the report is flagged as slow, and new requests arrive over SSE.
