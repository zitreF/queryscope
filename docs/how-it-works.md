# How QueryScope works

QueryScope is a Java agent: a jar the JVM loads before or next to the application, which rewrites the bytecode of a
few JDBC, Servlet and application methods as their classes load. This document walks through the pieces from the
moment the agent starts to the moment a finding shows up in the browser. For day to day use, see the
[usage guide](usage.md).

- [The big picture](#the-big-picture)
- [Getting into the JVM](#getting-into-the-jvm)
- [Instrumentation](#instrumentation)
- [Units of work](#units-of-work)
- [Tracing queries](#tracing-queries)
- [The call tree](#the-call-tree)
- [Analysis](#analysis)
- [The sampling profiler](#the-sampling-profiler)
- [The panel](#the-panel)
- [Memory and threads](#memory-and-threads)
- [Overhead](#overhead)
- [Testing](#testing)
- [Code layout](#code-layout)

## The big picture

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

Flight Recorder samples ─> JfrSampler ─> SampleStore ─> Profile ─> /api/profile ─> flame graph
```

Everything happens inside the application's JVM. Advice code that ByteBuddy inlines into JDBC, Servlet and
application methods reports to a few static tracers. They collect a **unit of work** per thread, an HTTP request or a
task, and when it ends hand the finished trace to the analyzer. The report goes to the application's log, to an
in-memory store behind the panel, and to the summary printed at exit. Independently of all that, Flight Recorder
samples the stacks of running threads, and the panel turns those samples into flame graphs on demand.

## Getting into the JVM

There are three entry points, and all of them end in `QueryScopeAgent.start`.

```
java -javaagent:queryscope.jar -jar app.jar ───────────────────────────────── premain ──┐
java -jar queryscope.jar app.jar ─> launcher ─> new JVM with -javaagent ───── premain ──┤
java -jar queryscope.jar ─> hub ─> Attach API, loadAgent into a running JVM ─ agentmain ┤
                                                                                         ▼
                                                                         QueryScopeAgent.start
```

- **`premain`** runs before the application's `main` when the JVM is started with `-javaagent`. The manifest names
  `QueryScopeAgent` as `Premain-Class` and allows class retransformation.
- **The launcher** (`QueryScopeLauncher`, the jar's `Main-Class`) splits its arguments into QueryScope options and the
  application, then starts the application in a child JVM with `-javaagent` pointing at its own jar. It passes on the
  JVM options of its own command line except other agents and debuggers, adds
  `-XX:FlightRecorderOptions=stackdepth=256` unless the user set it or the JVM is OpenJ9, and sets
  `-Dqueryscope.launcherPid`. The agent watches that process with `ProcessHandle.onExit` and exits the application
  when the launcher dies, so a killed launcher never leaves an application holding its port. Ctrl+C reaches both.
- **`agentmain`** runs when the hub loads the agent into a running JVM through the JDK's Attach API
  (`VirtualMachine.attach`, `loadAgent`). The manifest names the same class as `Agent-Class`.

`start` runs once per JVM, guarded by an `AtomicBoolean`. Only invalid options stop the application from starting;
any other exception or linkage error during startup is logged and leaves the application running without QueryScope,
and one in the panel leaves it running without the panel. Once the panel is up, the agent stores its port in the
system property `queryscope.panelPort`. The hub reads that property through the Attach API before and after loading
the agent, so attaching twice, or attaching to an application that was started with `-javaagent`, finds the existing
panel instead of loading a second agent.

**The hub** (`cli.HubCommand`, `HubServer`, `LocalProcesses`) is what `java -jar queryscope.jar` starts without
an application. It lists the JVMs of the current user with `VirtualMachine.list()`, picks a free port for every agent
it loads, and passes its own command line options on to them. `--attach=<pid>` does one attach from the command line.

## Instrumentation

`AgentInstaller` builds one ByteBuddy `AgentBuilder` from a table of hooks. Each hook is a type matcher, a method
matcher and an advice class:

| Types                                            | Methods                                             | Advice                  |
|--------------------------------------------------|-----------------------------------------------------|-------------------------|
| implementations of `FilterChain`, both namespaces | `doFilter(request, response)`                      | `ServletAdvice`         |
| `HttpServlet`, both namespaces                   | `service(request, response)`                        | `ServletAdvice`         |
| implementations of `java.sql.Connection`         | `prepareStatement`, `prepareCall`                   | `PrepareStatementAdvice` |
| implementations of `java.sql.Connection`         | `close`, `commit`, `rollback`                       | `ConnectionReleaseAdvice` |
| implementations of `java.sql.Statement`          | `execute*(String ...)`                              | `ExecuteSqlAdvice`      |
| implementations of `java.sql.Statement`          | `execute*()`, `executeBatch`, `executeLargeBatch`   | `ExecutePreparedAdvice` |
| implementations of `PreparedStatement`           | `setXxx(int, value)` and `setXxx(int, value, x)`    | `ParameterAdvice`       |
| implementations of `PreparedStatement`           | `clearParameters()`                                 | `ClearParametersAdvice` |
| implementations of `java.sql.ResultSet`          | `next()`                                            | `RowAdvice`             |
| the classes named by `entry=`                    | the methods named by `entry=`                       | `EntryAdvice`           |
| classes of your packages                         | every method except constructors, bridges, synthetic and native ones | `MethodAdvice` |
| the JDK's `com.sun.net.httpserver.Filter$Chain`  | `doFilter(exchange)`                                | `JdkHttpServerAdvice`   |
| `ThreadPoolExecutor`, `ForkJoinPool`             | `execute`, `submit`, `invoke`                       | `AsyncSubmitAdvice`     |
| `ThreadPerTaskExecutor`                          | `start(Runnable)`                                   | `AsyncSubmitAdvice`     |
| `ThreadBuilders`, behind every virtual thread    | `newVirtualThread(..., Runnable)`                   | `AsyncThreadAdvice`     |
| `ForkJoinTask`                                   | `fork()`                                            | `AsyncForkAdvice`       |
| `FutureTask`, `ForkJoinTask`, the tasks of `CompletableFuture` | `run()`, `doExec()`                    | `AsyncRunAdvice`        |

The choices behind it:

- **Matched by interface, never by implementation.** Types are found by the JDBC and Servlet interfaces they
  implement, so any driver, pool or container works without QueryScope knowing its classes.
- **Inlined advice, no new members.** `disableClassFormatChanges()` and `Advice` inline the enter and exit code into the
  original methods. No fields or methods are added, which is what makes retransformation of already loaded classes
  possible, and so the attach mode.
- **Retransformation.** `RedefinitionStrategy.RETRANSFORMATION` also rewrites classes that were loaded before the agent,
  which matters for the JDK's own classes at startup and for everything when attaching.
- **Failures stay inside.** Every advice uses `suppress = Throwable.class`, so an exception inside QueryScope never
  reaches the application, and a class ByteBuddy cannot transform is logged and left as it was.
- **No servlet dependency.** `Servlets` reads the method, URI, query string, dispatcher type and status through
  `MethodHandle`s looked up once per request class, so one advice serves `jakarta.servlet` and `javax.servlet`.
- **A bridge for the JDK's HttpServer.** Its handlers are usually lambdas, which cannot be instrumented, so the hook
  sits on `Filter.Chain.doFilter` inside the JDK, which every exchange passes. Code in the JDK cannot see classes on
  the application's class path, so `HttpServerBridge` is injected into the bootstrap class loader and given a read
  edge from the `jdk.httpserver` module. The inlined advice only calls the bridge, and the bridge hands the exchange
  to `JdkHttpServerRequests` in the agent, which opens and closes the request. Exchanges on QueryScope's own threads,
  the panel and the hub, are left out.
- **The same bridge idea for async work.** `AsyncBridge` lives in the bootstrap loader too, because its callers are
  `java.util.concurrent` classes. It is connected to the agent through `Supplier`, `Predicate` and `Consumer` only: a
  type of its own in a signature the agent calls would be loaded once by the application's class loader while the
  agent's classes are verified, and once more by the bootstrap loader, and the JVM refuses to link the two.
- **Isolation.** The agent ignores its own classes, ByteBuddy, `java.*`, `jdk.*`, `sun.*` and `com.sun.*`. ByteBuddy and
  Jackson are shaded into `me.fertiz.queryscope.agent.shaded`, so they never clash with the ByteBuddy that Hibernate
  brings or the Jackson of a Spring Boot app, whatever their versions, and `Nexus` injection is turned off because
  inlined advice does not need it. The service files of the shaded jars are renamed with their classes, so an
  application that looks up Jackson through `ServiceLoader` never finds QueryScope's copy.

## Units of work

`UnitTracer` keeps the current unit of work of each thread in a `ThreadLocal<ActiveUnit>`.

- **HTTP requests** start in `ServletAdvice` when a `REQUEST` dispatch enters the first `FilterChain.doFilter` or
  `HttpServlet.service` on the thread, and end when that call returns. Forwards, includes and nested filters land in
  the same request. Requests of the JDK's `HttpServer` start and end with the outermost `Filter.Chain.doFilter`.
- **Tasks** start with the first query that runs outside a request. A task ends when its connection is closed or its
  transaction committed or rolled back, after a second without queries, or at 1000 queries, so a long batch splits
  into readable pieces. A task ended by its connection lasts until that moment; one that ends because it went idle or
  something else started on the thread lasts until its last query or the last return from one of your methods,
  whichever came later, so the wait for the next query never counts. It is named after the outermost method of your
  packages that is on the stack, or the nearest application frame when there is no call tree.
- **Entry tasks** start and end with a method named by `entry=`, so a whole job is one unit however many connections
  it opens.
- **Async tasks** belong to the request or task that submitted them. When a task is handed to a `ThreadPoolExecutor`
  or `ForkJoinPool`, forked as a `ForkJoinTask`, or given to a new virtual thread, from a thread inside a unit, the task
  object is remembered with that unit and the unit counts it as running. `FutureTask`s and `ForkJoinTask`s keep their
  identity; a plain `Runnable` or `Callable` is wrapped in a small tracked one. When the task starts on a pool thread, that thread gets
  an async unit of its own, and when the task ends, its queries and call tree are handed to the owner. A task that
  runs on the owner's own thread, as when a pool runs it on the caller or a thread helps with a `join`, records into
  the owner directly. The queries keep their time relative to the owner's start, after the owner's own queries, and
  the call tree gets one `(async tasks)` node with the methods of all tasks merged by name. An owner that returns
  while tasks are still running waits for them for up to a second before it is published, which is what makes
  `future.get()` and `join()` safe: they return before the pool thread has left `run()`. Tasks that run longer, or
  start after the owner was published, are published on their own as `Async from GET /path`.
An `ActiveUnit` records its start in wall-clock and monotonic time, its thread ID, the number of classes the JVM had
loaded, its queries and its call tree. When it ends it becomes an immutable `UnitTrace`. Units that ran neither SQL
nor any of your methods are dropped.

## Tracing queries

- **One query, one record.** Pools wrap the driver's statements in their own, so `HikariProxyPreparedStatement.execute`
  calls `JdbcPreparedStatement.execute`. A per-unit call depth counter counts only the outermost call.
- **SQL of prepared statements.** `execute()` on a prepared statement carries no SQL. `PrepareStatementAdvice` stores
  the SQL of every statement it returns in a `BoundStatement`, and `ParameterAdvice` adds each value bound with
  `setXxx`, formatted by `ParameterValues`: strings quoted and cut at 64 characters, binary values as their size,
  `null` as `null`. The states live in a `WeakIdentityMap`, a map keyed by identity with weak references, so a
  statement the application drops is never kept alive by QueryScope.
- **Rows.** A `ResultSet` returned by a query goes into the same kind of map, pointing at the query's record, and
  `RowAdvice` counts every `next()` that returns `true`.
- **Call sites.** `StackWalker` finds the first frame outside the JDK, the agent, generated proxies and a list of known
  framework, pool, driver and logging packages, which is the line of application code that ran the query. Packages
  given with `package=` always count as application code, even inside a framework namespace.

## The call tree

`CallTracer` keeps a stack of your methods for every thread, in parallel arrays for the method, its start time, its
node and a recursion flag, so an enter or exit allocates nothing. `MethodAdvice` passes the method name as a constant
and gets back the depth of its frame, which its exit uses to pop everything above, so a frame that was skipped by an
exception never corrupts the stack.

- While a unit of work runs, every call lands in a `CallTreeBuilder` node keyed by its parent and method, which counts
  calls and time. Each query is attached to the node on top of the stack.
- When a task starts in the middle of your code, the frames already on the stack become nodes marked as already
  running, timed from the start of the task.
- Direct recursion folds into one node. A tree stops growing at 1000 nodes or 100 levels and says so.
- Self time is a node's total time minus the total time of its children. It includes the time in libraries the method
  calls and in its own queries.

**Your packages** come from `AppPackages`: the `package` option, or the package of the main class read from the
`sun.java.command` system property. For a jar it reads `Start-Class` (Spring Boot) or `Main-Class` from the manifest.
Framework launchers and single-segment packages are rejected, and generated classes (`$$` and `$HibernateProxy$`)
are never instrumented.

## Analysis

`SqlNormalizer` turns number and string literals into `?`, collapses `IN (1, 2, 3)` to `IN (?)`, and drops comments,
case and extra whitespace, so queries that differ only in their values compare equal.

`QueryAnalyzer` turns a trace into a report with findings:

- **N+1**: a normalized SELECT (or `WITH`) that runs at least `nPlusOneThreshold` times, reported at the call site that
  ran most of the repeats.
- **Unbatched writes**: a normalized INSERT, UPDATE, DELETE, MERGE, UPSERT or REPLACE that runs as often.
- **Duplicate query**: the same SELECT with the same parameters twice, unless all its runs are part of an N+1.
- **Slow query** and **large result**: every query at or above `slowQueryMs`, or with at least `largeResultRows` rows.
- **Too many queries**: a unit of work with `maxQueries` queries or more, with the share of its time in the database.
- **Slow code**: a method whose self time minus the time of its own queries reaches `slowMethodMs`, summed over its
  calls in the unit, at most three per unit. Methods that were already running when a task started are left out
  because their time is only partly known, and nothing is reported when the JVM loaded 50 classes or more during the
  unit, which marks it as warm-up.

`ConsoleReporter` logs each report through `System.Logger`, so it ends up in whatever logging the application uses.
`SessionSummary` groups reports by endpoint, with numeric and UUID path segments folded into `{id}` and `{uuid}`, and
prints its table from a shutdown hook, together with the hottest methods of the sampling profiler.

## The sampling profiler

- **Sampling.** `JfrSampler` opens a Flight Recorder `RecordingStream` with `jdk.ExecutionSample` and
  `jdk.NativeMethodSample` at `sampleMs` and consumes it on a daemon thread of its own (the stream's own thread from
  `startAsync` is not a daemon and would keep the JVM alive). For each event it keeps the thread, the time, the frames
  as `Class.method` and whether the stack was truncated. Hidden class suffixes such as `/0x0000…` are dropped, so the
  frames of the same lambda form merge. Where Flight Recorder is missing, or its stream fails to start, for example
  because it cannot write its repository, `ThreadDumpSampler` calls `Thread.getAllStackTraces()` every 20 ms or more
  and keeps runnable threads.
- **Storage.** `SampleStore` keeps the samples of the last 15 minutes, at most 200,000, as method IDs. Identical stacks
  are stored once, so a hot loop sampled thousands of times costs one array.
- **On exit** the stream is stopped, which on Java 20 and newer waits until the last events are consumed, so the
  session summary also counts the final second.
- **Profiles.** `Profile.build` filters the samples by time, thread and kind and merges their stacks into a tree with
  total and self counts per node. For one request it keeps the samples taken on the request's thread between its
  start and end; for an endpoint, those of every stored call. The *Your code* view keeps only the frames of your
  packages plus the leaf frame, and puts samples without any of your frames under `(outside your code)`. Samples whose
  stack contains QueryScope itself are counted apart, so the agent's own work, mostly instrumentation at startup, does
  not drown the application.
- **Hot methods** count, per method, the samples where it was the leaf (self), the samples where it was anywhere on
  the stack (total), and for your methods the samples where it was the deepest of your frames (own time including the
  libraries it calls).
- **JSON.** `ProfileJson` writes nodes with at least a thousandth of the samples, so a profile stays small enough for
  the browser however long the application ran.

## Tracing methods on demand

`MethodTracer` checks a `Class#method` name, refuses classes of the JDK and of QueryScope, and asks
`AgentInstaller.installMethodTrace` for a ByteBuddy transformer of its own that adds `MethodTraceAdvice` to that one
method. The transformer retransforms the class if it is already loaded, and every other transformer is applied again
on the way, so the JDBC hooks and the call tree stay in place. **Stop** resets the transformer, which retransforms
the class back.

The advice reads `System.nanoTime()` on entry and hands the duration to `MethodTraces`, a map from method name to its
`TracedMethod`. That keeps the count, failures and total in `LongAdder`s, the durations in a log-linear histogram of
eight buckets per power of two, so a percentile is off by at most an eighth, and the latest 50 and slowest 10 calls in
fixed arrays with their thread and the id and name of the unit of work they ran in, so recording a call allocates
nothing. While all calls of a method are among its latest 50, percentiles come from them exactly.

## Load testing

Every HTTP unit remembers the local port its request came in on, from `getLocalPort()` of the servlet request or the
local address of the JDK's `HttpExchange`. `LoadTestPlan.replaying` turns a stored request into a target on
`127.0.0.1` with that port, and the method, path, headers and body the user gave or the request's own. It accepts only
the methods `HttpURLConnection` can send, a body only with POST and PUT, header names that are HTTP tokens and values
without line breaks, so nothing can be smuggled into the request.

`LoadTester` runs one `LoadTest` at a time on up to 50 daemon threads that share a counter of requests to send. Each
request goes through `HttpURLConnection` without a proxy and without following redirects, and carries the header
`X-QueryScope-Load-Test` with the number of the test. The time from sending to reading the whole answer goes into an
array as long as the test, so the percentiles are exact.

On the server the servlet and `HttpServer` hooks read that header into the unit, and the agent hands every unit of a
test QueryScope started to the `LoadTester` instead of the report store, the console and the session summary. A test
adds up their queries, time in the database and durations, and counts each kind of finding once per request, grouped
the way the session summary groups them. When the last thread is done, the test waits up to two seconds for the
traces of the last answers before it is marked finished and logs its summary line. A header with a number QueryScope
never handed out is ignored, so it cannot hide requests from the panel.

## The panel

**Server.** `PanelServer` is the JDK's built-in `com.sun.net.httpserver`, bound to loopback on daemon threads. It
serves the static panel and a JSON API: the stored reports, their call trees, profiles, and a server-sent events
stream with one event per finished report. Every SSE client gets its own bounded queue, so a slow browser never holds
up an application thread.

**For AI assistants.** `AiReport` turns the stored reports, a profile of the last five minutes, the traced methods
and the last load tests into Markdown, grouping findings the way the session summary does. `/api/report.md` serves
it and the panel's **Copy for AI** copies it. `McpEndpoint` is a Model Context Protocol server on the Streamable HTTP transport in its
plainest form: each POST carries one JSON-RPC request and gets one JSON answer, notifications get `202`, and `GET`
gets `405` because the server never pushes messages. It answers `initialize`, `ping`, `tools/list` and `tools/call`
for eight tools that wrap the report, the request store, the method tracer and the load tester. A tool that fails answers with
`isError` and a sentence the assistant can act on.

All JSON goes through Jackson 3: the panel, the hub and the MCP server build their answers as Jackson trees and read
requests with `JsonMapper.readTree`, set to refuse text after the value and nesting deeper than 64 levels. Writing
allows 1000 levels, because the flame graph of a 256-frame stack is an object and an array per frame. Times are
rounded to the microsecond before they are written. Every request body is read up to a limit, 1 KB for most and
256 KB for a load test with a body of up to 64 KB, and a larger one is refused instead of cut.

**Security.** The panel and the hub listen on loopback only and reject requests whose `Host` header is not
`localhost`, `127.0.0.1` or `[::1]`, which stops DNS rebinding. The hub's attach endpoint, the only one that changes
anything outside QueryScope, also requires a JSON content type and a same-origin `Origin` header, so a web page on
another site cannot trigger an attach from the user's browser. Both pages are sent with `X-Frame-Options: DENY` and
`frame-ancestors 'none'`, so another site cannot load them in a hidden frame and trick a click onto **Attach**.

**Frontend.** The panel is plain HTML, CSS and JavaScript with no build step and no external requests. It loads the
stored reports once, then follows the SSE stream. The live stream is drawn on a canvas, the flame graph is laid out as
absolutely positioned elements with frames under 3 pixels skipped, call trees and profiles are fetched on demand, and
the SQL highlighter is a small tokenizer.

## Memory and threads

Everything QueryScope keeps has a limit:

| What                          | Limit                                              |
|-------------------------------|----------------------------------------------------|
| Reports with SQL              | the last 200                                       |
| Reports without SQL           | the last 200, kept apart so they never push out the ones with SQL |
| Call tree of one unit         | 1000 nodes, 100 levels                             |
| Queries of one task           | 1000, then a new task starts                       |
| Queries of a request or entry task | the first 1000 recorded, the rest only counted and timed |
| Samples                       | 15 minutes, at most 200,000                        |
| Session summary               | 500 endpoints, 200 distinct findings               |
| Traced methods                | 20, each with its 50 latest and 10 slowest calls   |
| Load tests                    | one at a time, the last 10 kept, 10,000 requests and 50 at once at most |
| Statements and result sets    | held weakly, gone with the application's objects   |
| Async tasks not started yet   | held weakly; a finished request waits at most 1 s for its running tasks |

QueryScope's threads are daemons, so they never keep the application alive: `queryscope-panel-*` for the panel,
`queryscope-flight-recorder` for the sampler, `queryscope-load-test-*` for load tests, `queryscope-async-wait` for
requests that wait for their async tasks, `queryscope-sampler-start` and `queryscope-panel-start` for startup, and
`queryscope-summary` as the shutdown hook. Samples taken on QueryScope's own threads are dropped. A finished unit is
analyzed on the thread that ran it, because a report handed to another thread right before the JVM stops would lose
its log line: logging frameworks close their handlers in shutdown hooks of their own.

## Overhead

Measured on the demo with 50 calls per endpoint after warm-up, the call tree and the sampler together changed the
average response time by less than a millisecond, within the noise of the measurement. Flight Recorder at 10 ms is
designed to stay around one percent of CPU. The call tree costs a few dozen nanoseconds per call of your methods,
which only shows in tight loops over tiny methods. Instrumenting classes costs time when they load, which is most
visible right after attaching to a large application.

The hooks on the JDK's executors were measured with load tests on the demo, three rounds each, with and without
`async=false` alternating: 870 against 881 requests per second on `/api/customers/optimized`, which runs no async work,
well inside the 10% spread between runs, and the same startup time. Outside a unit each hook costs a volatile read and
a `ThreadLocal` lookup.

Both bridges are added to the bootstrap class loader in one jar through `Instrumentation`, so the JVM prints once that
class data sharing now covers only the classes of the boot loader. Startup of the demo was the same within a quarter of
a second when the bridges were defined without the jar instead, through `jdk.internal.misc.Unsafe`, and that way makes
the JDK warn about a deprecated `sun.misc.Unsafe` method on every start, so the jar stays.

## Testing

`./gradlew build` runs every test, from units to real JVMs:

- **Unit tests** for the SQL normalizer, the analyzer, the options, the launcher and hub command lines, parameter
  formatting, the session summary, the call stack, package detection, the sample store, profiles, the JSON settings, the
  report store, the port fallback, a startup that fails, the AI report, method tracing, and the panel,
  MCP and hub servers, including SSE, the `Host` check, the frame headers and the attach protection.
- **`SamplingTest`** runs the Flight Recorder sampler and the thread dump sampler against a busy thread, and
  `JfrSamplerTest` checks the switch to thread dumps when the stream fails to start.
- **`AgentInstrumentationTest`** installs the agent into the test JVM with ByteBuddyAgent and runs real queries
  through HikariCP and H2 inside simulated `jakarta.servlet` and `javax.servlet` requests, outside any request and
  inside an `entry=` method, and checks the call tree of a profiled fixture class with recursion and exceptions, that
  a task's call tree fits in its duration, that a request past 1000 queries counts the rest, and that tracing a
  method at runtime records its calls and leaves its call tree intact, also after the trace is stopped.
- **`QueryScopeEndToEndTest`** starts the demo shop with `-javaagent:queryscope.jar` in a separate JVM and checks every
  finding, the call tree, the Flight Recorder samples, method tracing and the MCP report through the panel's API. The demo runs on the test classpath
  without the agent's own classes, so only the shaded jar is attached.
- **`LauncherEndToEndTest`** starts the demo through `java -jar queryscope.jar` and checks that killing the launcher
  takes the application and its summary down with it.
- **`AttachEndToEndTest`** starts the demo without QueryScope, starts the hub, attaches to the demo through the hub's
  API and checks that the agent finds the N+1 in the running application.
- **`PlainJavaEndToEndTest`** runs a plain `main` program on H2 with the agent and checks its task, the N+1, and that
  the program still exits and prints its summary, and a program that serves a request with the JDK's `HttpServer`.
- **`TomcatEndToEndTest`** deploys `bookshelf.war`, a servlet with its H2 driver in `WEB-INF/lib` that the
  `bookshelfWar` task packs, to a standalone Tomcat started with the agent, and checks the N+1 at the servlet's call
  site and its call tree. It runs only with `-Ptomcat=<Tomcat folder>`, so the normal build downloads nothing.

GitHub Actions runs the same build on Temurin 25 for every push and pull request.

## Code layout

```
me.fertiz.queryscope.agent             QueryScopeAgent (premain, agentmain), AgentInstaller
├── config                             AgentConfig, AgentProperties (system properties shared with launcher and hub)
├── appcode                            which classes are your code: AppPackages, CallSites
├── trace                              requests and tasks with their SQL: UnitTracer, ActiveUnit, LiveQuery,
│                                      BoundStatement, Servlets, AsyncTasks, AsyncWait, the JDBC, servlet, entry and
│                                      async advice, UnitTrace
├── bootstrap                          classes injected into the bootstrap loader: HttpServerBridge, AsyncBridge
├── calltree                           the call tree of your methods: CallTracer, CallTreeBuilder, MethodAdvice,
│                                      CallTree, CallNode, AsyncCalls
├── methodtrace                        tracing chosen methods: MethodTracer, MethodTraceAdvice, MethodTraces,
│                                      TracedMethod, LatencyHistogram
├── loadtest                           LoadTester, LoadTest, LoadTestPlan, LoadTestResult, LoopbackHttp
├── findings                           QueryAnalyzer, SqlNormalizer, Thresholds, FindingKind, FindingText and the
│                                      finding records
├── profiler                           Sampling, JfrSampler, ThreadDumpSampler, SampleStore, Profile
├── console                            ConsoleReporter, SessionSummary, AiReport
├── panel                              PanelServer, ReportStore, ReportJson, ProfileJson, ProfileQuery,
│                                      MethodTraceJson, LoadTestJson, LoadTestRequest, McpEndpoint
├── hub                                HubServer, LocalProcesses, JavaProcess
├── http                               LocalServer, HttpSupport, Json, Browser, shared by panel
│                                      and hub
└── cli                                QueryScopeLauncher (the jar's Main-Class), LaunchCommand, HubCommand
```

Dependencies point one way, with no package cycles: `cli` uses `hub`, `http` and `config`; the agent's root package
uses the feature packages; `panel`, `console`, `loadtest`, `findings` and `trace` build on each other in that
order; and `appcode`, `http` and `config` (apart from `findings.Thresholds`) depend on nothing else in QueryScope.

The web pages live in `src/main/resources/me/fertiz/queryscope/agent/web`. The tests mirror the packages, and
`src/test` also holds the demo shop (`demo`), a plain JDBC program (`plain`), a profiled fixture (`profiled`) and the
end-to-end tests (`e2e`).
