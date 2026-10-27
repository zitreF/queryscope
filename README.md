<h1 align="center">QueryScope</h1>

<p align="center">
  <b>Find N+1 and slow SQL queries in a Spring Boot app without touching its code.</b><br>
  A Java agent that watches every JDBC call, groups the queries of each HTTP request and shows them on a live timeline.
</p>

<p align="center">
  <a href="https://github.com/zitreF/queryscope/actions/workflows/build.yml"><img alt="Build" src="https://github.com/zitreF/queryscope/actions/workflows/build.yml/badge.svg"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-ED8B00?logo=openjdk&logoColor=white">
  <img alt="Spring Boot 3 and 4" src="https://img.shields.io/badge/Spring%20Boot-3%20%7C%204-6DB33F?logo=springboot&logoColor=white">
  <img alt="ByteBuddy 1.18" src="https://img.shields.io/badge/ByteBuddy-1.18-4B6BFB">
  <img alt="No dependencies in your app" src="https://img.shields.io/badge/dependencies%20in%20your%20app-none-2EA44F">
</p>

![QueryScope SQL Live view with requests streaming in](docs/panel.png)

```bash
java -jar queryscope.jar your-app.jar
```

That is the whole setup: QueryScope starts your app with the agent attached. Open http://localhost:7070, or add
`--open` and QueryScope opens it for you, then use the app. Ctrl+C stops both and prints a summary of the session.

## Features

- **No code changes.** Attach the agent with `-javaagent`. Nothing goes into your `pom.xml` or `build.gradle`.
- **N+1 detection.** The same SELECT repeated within one request is reported together with the line of your code
  that triggered it, for example `CustomerSummary.of(CustomerSummary.java:12)`.
- **Slow queries.** Every query above a threshold you choose, 100 ms by default.
- **More checks.** Unbatched writes (the same INSERT, UPDATE or DELETE repeated in one request), duplicate queries
  (the same SELECT with the same bound parameters run twice), large results (a query that returns 500 rows or more)
  and requests that simply run too many queries. Every query shows its parameters and the rows it returned.
- **Two areas.** The panel has an SQL tab for queries and a Java tab for your code, and a request found in one
  opens in the other with one click.
- **Live tracking.** Keep the app running and use it: the SQL tab's Live view draws every request on a moving time
  axis, with one tick per SQL query, N+1 requests in red and slow ones in yellow. Below are live counters, a table of
  endpoints by time spent in the database and the latest findings. A short notice appears whenever a new problem
  shows up.
- **Session summary.** When the app stops, QueryScope prints every endpoint and task with its calls, queries per
  call, average time and time in the database, followed by the findings that came up most often. IDs in paths are
  folded, so `/api/customers/7` and `/api/customers/8` count as `GET /api/customers/{id}`.
- **Request details.** The SQL tab's Requests view shows each request's findings, its queries on a timeline and the
  full SQL with syntax highlighting.
- **Call tree.** The Java tab shows a tree of your own methods for every request and task, also the ones that ran
  no SQL: calls, total and self time, and the queries each method ran, so an N+1 points at the method that loops and a
  click on it lists its SQL. A table ranks the methods by self time, and a method that spends too long in Java rather
  than in SQL is reported as slow code. Your package is taken from the main class, or set with `package=`.
- **Any data access code.** Works under Hibernate, Spring Data JPA, `JdbcClient`, `JdbcTemplate` or plain JDBC, with
  any driver and connection pool.
- **Not only HTTP.** Queries that run outside a request, in scheduled jobs, message listeners or a plain `main`, are
  grouped into tasks, one per connection or transaction, and any method can become its own task with `entry=`.
- **Stays out of the way.** ByteBuddy is shaded, the panel listens on localhost only, its threads are daemons, and an
  exception inside the instrumentation is swallowed instead of breaking a request.

## Quick start

The tests contain a small demo shop with a deliberate N+1, its fixed version and a slow report. `runDemo` builds the
agent and starts the shop with it attached:

```bash
git clone https://github.com/zitreF/queryscope.git
cd queryscope
./gradlew runDemo
```

On Windows use `gradlew.bat`. Open the panel at http://localhost:7070 and call the endpoints:

| Endpoint                                            | What it does                                                        | QueryScope shows        |
|-----------------------------------------------------|---------------------------------------------------------------------|-------------------------|
| http://localhost:8080/api/customers/naive           | `findAll()`, then reads each customer's lazy `orders` collection    | 41 queries, N+1 ×40     |
| http://localhost:8080/api/customers/optimized       | the same summaries with `left join fetch c.orders`                  | 1 query, no findings    |
| http://localhost:8080/api/reports/customer-activity | joins 150k audit events with customers on an unindexed email column | slow query, about 0.5 s |
| http://localhost:8080/api/customers/7/card          | looks the customer's name up twice through the same helper          | duplicate query `[7]`   |
| http://localhost:8080/api/reports/recent-returns    | loads every return event and keeps the 20 newest in memory          | 50,000 rows             |
| `curl -X POST localhost:8080/api/reports/visits`    | inserts 20 events one statement at a time                           | 20 unbatched writes     |
| http://localhost:8080/api/reports/daily-revenue     | builds a year of daily revenue as CSV with `+=` in a loop           | slow method             |

The demo also runs a scheduled job every 30 seconds that builds the same naive summaries outside any HTTP request. It
shows up as the task `DigestJob.prepareDigest` with the same N+1, and the data loaded at startup shows up as
`DemoApplication.main` with its 150k-row insert flagged as slow.

## Example: finding and fixing an N+1

A customer in the demo has a lazy list of orders:

```java
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private Long id;

    private String name;

    @OneToMany(mappedBy = "customer")
    private List<CustomerOrder> orders = new ArrayList<>();
}
```

The service builds a summary of every customer, with the number of orders and the money spent:

```java
public List<CustomerSummary> summariesNaive() {
    return customers.findAll(Sort.by("id")).stream()
            .map(CustomerSummary::of)
            .toList();
}
```

The code looks innocent. `findAll` loads the 40 customers in one query, but `CustomerSummary.of` reads
`customer.getOrders()`, and because the collection is lazy, Hibernate goes back to the database once for every
customer.

**1. Run the app with the agent and call the endpoint.**

```bash
./gradlew runDemo
curl http://localhost:8080/api/customers/naive
```

**2. See what QueryScope found.** The request appears in the SQL tab's Live view as a red bar, and its Requests
view shows it with an `N+1 ×40` badge, the 40 repeated queries red on its timeline, and the line that runs them:

![QueryScope SQL requests view with the N+1 request opened](docs/panel-n-plus-one.png)

**Call tree** opens the same request in the Java tab, where the 40 queries sit on `CustomerSummary.of`, called once
per customer from `CustomerService.summariesNaive`:

![QueryScope Java call tree of the N+1 request](docs/panel-call-tree.png)

The same goes to the app's own log:

```
INFO  QueryScope : GET /api/customers/naive -> 200, 41 queries in 46.9 ms
WARN  QueryScope : N+1: 40 x select o1_0.customer_id,o1_0.id,o1_0.placed_at,o1_0.total from orders o1_0 where o1_0.customer_id=? at me.fertiz.queryscope.demo.customer.CustomerSummary.of(CustomerSummary.java:12)
```

**3. Fix it** by loading the orders together with the customers:

```java
@Query("select c from Customer c left join fetch c.orders order by c.id")
List<Customer> findAllWithOrders();
```

**4. Check again.** The fixed endpoint returns exactly the same summaries with one query and no warning:

```
INFO  QueryScope : GET /api/customers/optimized -> 200, 1 query in 21.8 ms
```

Slow queries are reported the same way. The demo report joins two tables on a column without an index, so the
database compares every audit event with every customer:

```
INFO  QueryScope : GET /api/reports/customer-activity -> 200, 1 query in 532.1 ms
WARN  QueryScope : Slow query: 530.5 ms select c.name as customer, count(*) as events from audit_events e join customers c on c.email = e.actor_email group by c.name order by events desc limit ? at me.fertiz.queryscope.demo.report.AuditReportService.mostActiveCustomers(AuditReportService.java:28)
```

![QueryScope panel showing a slow query](docs/panel-slow-query.png)

## Attaching it to your own app

Build the agent once with `./gradlew shadowJar`, which writes `build/libs/queryscope.jar`.

**Let QueryScope start it**

```bash
java -jar queryscope.jar your-app.jar
java -jar queryscope.jar --open --port=9090 --slowQueryMs=250 your-app.jar --server.port=8081
java -Xmx1g -Dspring.profiles.active=local -jar queryscope.jar -cp "lib/*" com.acme.Main
```

Options before the jar go to QueryScope, arguments after it go to the app, and JVM options of the `java` command
are passed on to the app. Ctrl+C stops both, and if the launcher is killed the app shuts itself down instead of
holding on to its port. `java -jar queryscope.jar --help` lists the options.

**Attach it yourself**: pass the jar to the JVM that runs your app.

```bash
java -javaagent:/path/to/queryscope.jar -jar your-app.jar
```

**Gradle `bootRun`** (`build.gradle.kts`)

```kotlin
tasks.bootRun {
    jvmArgs("-javaagent:/path/to/queryscope.jar")
}
```

**Maven**

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-javaagent:/path/to/queryscope.jar"
```

**IntelliJ IDEA**: *Run → Edit Configurations → Modify options → Add VM options*, then enter
`-javaagent:/path/to/queryscope.jar`.

### Options

With `-javaagent` the options go after the jar path, separated by commas. The launcher takes the same options as
`--name=value` arguments:

```bash
java -javaagent:/path/to/queryscope.jar=port=9090,nPlusOneThreshold=3,slowQueryMs=250,entry=com.acme.ImportJob#run -jar your-app.jar
```

| Option              | Default | Meaning                                                      |
|---------------------|---------|--------------------------------------------------------------|
| `port`              | `7070`  | Port of the panel on localhost                               |
| `nPlusOneThreshold` | `5`     | Runs of the same SELECT, or write, that make an N+1          |
| `slowQueryMs`       | `100`   | Queries taking at least this long are reported as slow       |
| `largeResultRows`   | `500`   | Queries returning at least this many rows are reported       |
| `maxQueries`        | `30`    | Requests or tasks running at least this many queries         |
| `slowMethodMs`      | `100`   | Methods spending at least this long in their own code        |
| `package`           | main's  | Package whose methods form the call tree, can be repeated    |
| `profile`           | `true`  | Records the call tree of your methods                        |
| `entry`             | none    | `Class#method` whose call becomes one task, can be repeated  |
| `open`              | `false` | Opens the panel in the default browser once it is running    |
| `summary`           | `true`  | Prints the session summary when the JVM stops                |

`open`, `summary` and `profile` work without a value too, so `--open` and `open` mean `open=true`. An unknown option or an
invalid value stops the JVM at startup with a message that names it. If the port is taken, the panel is skipped and
findings still go to the log.

QueryScope needs Java 17 or newer and works with any Java app that talks to its database through JDBC:

- **HTTP requests** are recognized in any servlet container, on `jakarta.servlet` and `javax.servlet` alike, so
  Spring Boot 2, 3 and 4, Tomcat, Jetty and Undertow all work.
- **Everything else** becomes a task: a scheduled job, a message listener, a batch run or a CLI. A task starts with
  the first query outside a request and ends when the connection is closed or the transaction committed, and it is
  named after the application method that ran the queries, such as `ImportJob.run`. With `entry=` a whole method
  call is one task, however many connections it uses.

The end-to-end tests run the demo on Spring Boot 4.1 with Tomcat, HikariCP and H2, and a plain `main` program on bare
JDBC, and `javax.servlet` is covered by in-process tests. QueryScope is a development tool that keeps the SQL and
call trees of the last requests and tasks in memory, together with their bound parameters (strings cut at 64
characters, binary values shown as their size), so don't attach it in production.

## Using the results in scripts and tests

The panel is backed by a small JSON API on the same port:

| Endpoint                       | Returns                                                                                                              |
|--------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `GET /api/requests`            | the last 200 requests that ran SQL and the last 200 that only ran your code, newest first, with queries and findings |
| `DELETE /api/requests`         | clears them                                                                                                          |
| `GET /api/events`              | a server-sent events stream with one `request` event per finished request                                            |
| `GET /api/requests/{id}/calls` | the call tree of one stored request, with the queries of each method                                                 |

For example, after clicking through the demo, list the requests that have a finding:

```bash
curl -s localhost:7070/api/requests | jq -c '.[] | select(.findings != []) | {path, findings: [.findings[].type]}'
```

```
{"path":"/api/reports/customer-activity","findings":["slowQuery"]}
{"path":"/api/customers/naive","findings":["nPlusOne"]}
```

Each finding carries the normalized SQL, the call site and the indexes of the queries it covers:

```
{
  "type": "nPlusOne",
  "sql": "select o1_0.customer_id,o1_0.id,o1_0.placed_at,o1_0.total from orders o1_0 where o1_0.customer_id=?",
  "callSite": "me.fertiz.queryscope.demo.customer.CustomerSummary.of(CustomerSummary.java:12)",
  "executions": 40,
  "totalMs": 10.039,
  "queryIndexes": [1, 2, 3, 4, ...]
}
```

That makes it easy to guard against new N+1 queries: start the app with the agent in an end-to-end test, call the
endpoint, and fail when its request has a finding. The [end-to-end test](src/test/java/me/fertiz/queryscope/e2e/QueryScopeEndToEndTest.java)
of this repository does exactly that for the demo.

## How it works

```
HTTP request ─> FilterChain.doFilter ─opens─> unit of work (one per thread)
                                                   ▲
Methods of your packages, enter and exit ──────────┤ call tree
Connection.prepareStatement, setXxx ───────────────┤ SQL and bound parameters
Statement / PreparedStatement.execute* ────────────┤ time and call site
ResultSet.next ────────────────────────────────────┤ rows
                                                   │ request or task ends
                                                   ▼
                               QueryAnalyzer + SqlNormalizer ─> findings
                                                   │
              ┌────────────────────────────────────┼────────────────────────────────────┐
              ▼                                    ▼                                    ▼
  ConsoleReporter (app log)    ReportStore ─> PanelServer ─SSE─> browser    SessionSummary (on exit)
```

- **AgentInstaller** is a table of hooks: `FilterChain.doFilter` and `HttpServlet.service` in both servlet namespaces,
  `prepareStatement`, `close`, `commit` and `rollback` on every `java.sql.Connection`, the `execute` methods of every
  `java.sql.Statement`, one hook per `entry=` method and one for every method of the classes in your packages.
  Types are matched by the JDBC and Servlet interfaces they
  implement, never by driver, pool or container classes. The first `REQUEST` dispatch on a thread opens a trace and
  its exit closes it, so queries run by filters, forwards and the servlet all land in one request.
- **Servlets** reads the method, URI and status through `MethodHandle`s looked up once per request class, so one
  advice serves `jakarta.servlet` and `javax.servlet` and the agent has no compile-time servlet dependency.
- **RequestTracer** times every query. A call-depth counter makes sure a query is counted once when a pool proxy wraps
  the driver (`HikariProxyPreparedStatement` calling H2's `JdbcPreparedStatement`). `execute()` on a prepared
  statement carries no SQL, so the tracer remembers it from `prepareStatement`, together with the values bound by
  every `setXxx` call, in a weak identity map that never keeps statements alive. Each returned `ResultSet` is tracked
  the same way and counts its rows as `next()` is called. `StackWalker` records the first frame outside the JDK,
  frameworks, drivers and generated proxies, which is the line of application code that ran the query. A query with
  no request around it opens a task instead, named after the outermost method of yours on the thread, which ends on
  `close`, `commit` or `rollback`, after a second without queries, or at 1000 queries.
- **CallTracer** keeps a stack of your methods per thread, fed by an advice inlined into each of them. While a request
  or task runs, every call lands in a tree node keyed by its parent and method, which counts calls and time, and each
  query is added to the method on top of the stack. Methods already running when a task starts are kept and marked,
  direct recursion folds into one node, and a tree stops growing at 1000 methods or 100 levels. Packages come from the
  main class, or from `Start-Class` and `Main-Class` in the manifest of the jar, and generated proxies are skipped.
- **SqlNormalizer** turns number and string literals into `?`, collapses `IN (1, 2, 3)` to `IN (?)`, and drops
  comments, case and extra whitespace, so queries that differ only in their values group together.
- **QueryAnalyzer** reports a SELECT that runs at least `nPlusOneThreshold` times in one request as N+1, naming the
  call site behind most of the repeats, and a write repeated as often as unbatched. The same SELECT with the same
  parameters run twice is a duplicate, unless it is already part of an N+1. Every query that takes at least
  `slowQueryMs` is slow, every query returning `largeResultRows` rows is a large result, and a unit of work with
  `maxQueries` queries or more gets a finding of its own with the share of its time spent in the database. A method
  whose self time minus its own queries reaches `slowMethodMs`, summed over its calls, is slow code, unless the JVM
  loaded 50 classes or more during the request, which means the times are mostly warm-up.
- **SessionSummary** collects every report by endpoint, with numeric and UUID path segments folded into `{id}` and
  `{uuid}`, and prints its table from a JVM shutdown hook, so it also appears when a launcher-started app is stopped.
  Endpoints and distinct findings are capped, so a long session never grows without bound.
- **PanelServer** is the JDK's built-in `com.sun.net.httpserver`, bound to loopback. Every SSE client gets its own
  queue, so a slow browser never holds up an application thread, and requests with a foreign `Host` header are
  rejected to stop DNS rebinding. The panel itself is plain HTML, CSS and JavaScript with no build step and no
  external requests: the live stream is drawn on a canvas, the SQL highlighter is a small tokenizer.

ByteBuddy is shaded into `me.fertiz.queryscope.agent.shaded`, so it never clashes with the ByteBuddy that Hibernate
brings, and the agent is compiled for Java 17.

## Limitations

- Queries a request runs on another thread (`DeferredResult`, `@Async`, `CompletableFuture`) show up as separate tasks
  rather than as part of the request.
- Reactive stacks are not covered: WebFlux and Vert.x requests are not recognized, and R2DBC is not JDBC at all.
- HTTP servers that are not servlet containers, such as the JDK's own `HttpServer` or Netty, are not recognized as
  requests. Their queries still show up as tasks.
- `Statement.addBatch(sql)` batches are not recorded. Prepared statement batches are.
- The call site skips `org.springframework.*` frames, so apps whose own code lives under that package (such as Spring
  PetClinic) show the nearest frame outside it.
- The panel listens on loopback only, so inside a container it has to be reached from the same network namespace.
- The call tree costs a few dozen nanoseconds per call of your methods. That is fine while developing, but a tight
  loop calling tiny methods millions of times gets noticeably slower; `profile=false` turns it off.
- Lambdas, constructors and default methods of interfaces are not nodes of their own: what they call shows up under
  the method that runs them.

## Project layout

```
src/main/java/me/fertiz/queryscope/agent/    the agent: instrument, trace, analysis, report and panel
src/main/resources/.../agent/panel/          the web panel (HTML, CSS and JavaScript)
src/test/java/me/fertiz/queryscope/agent/    unit tests
src/test/java/me/fertiz/queryscope/demo/     demo shop on Spring Boot, JPA and H2 with an N+1, its fix and a slow report
src/test/java/me/fertiz/queryscope/plain/    a plain Java program on bare JDBC, without Spring
src/test/java/me/fertiz/queryscope/e2e/      runs the demo, the launcher and the plain program with the real agent
```

## Building and testing

```bash
./gradlew build
```

Java 25 toolchain, Gradle 9 with Kotlin DSL (wrapper included), Shadow for the agent jar. `build` runs every test:

- Unit tests for the normalizer, the analyzer, the options, the launcher's command line, the parameter formatting, the
  session summary, the call stack, the package detection, the JSON writer, the report store and the panel server,
  including SSE and the `Host` check.
- `AgentInstrumentationTest` installs the agent into the test JVM with ByteBuddyAgent and runs real queries through
  HikariCP and H2 inside simulated `jakarta.servlet` and `javax.servlet` requests, outside any request, and inside an
  `entry=` method, and checks the call tree of a profiled fixture class, with recursion and exceptions.
- `CustomerServiceTest` checks that the demo's optimized endpoint returns exactly the same summaries as the naive one.
- `QueryScopeEndToEndTest` starts the demo shop with `-javaagent:queryscope.jar` as a separate process on free ports
  and checks through the panel API that the naive endpoint is an N+1 at `CustomerSummary.of`, the optimized one runs a
  single query with no findings, the report is flagged as slow, duplicates, unbatched writes and large results are
  found, the call tree leads from the controller down to `CustomerSummary.of`, the CSV export is slow code, and new
  requests arrive over SSE. The demo runs on the test classpath without the agent's own classes, so
  only the shaded `queryscope.jar` is attached.
- `LauncherEndToEndTest` starts the demo through `java -jar queryscope.jar` and checks that killing the launcher takes
  the app down with it.
- `PlainJavaEndToEndTest` runs a plain `main` program on H2 with the agent and checks the N+1 in its task and the
  session summary printed when it exits.
