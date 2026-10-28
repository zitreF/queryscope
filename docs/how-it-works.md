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

**The hub** (`HubCommand`, `HubServer`, `LocalProcesses`) is what `java -jar queryscope.jar` starts without
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
- **Isolation.** The agent ignores its own classes, ByteBuddy, `java.*`, `jdk.*`, `sun.*` and `com.sun.*`. ByteBuddy is
  shaded into `me.fertiz.queryscope.agent.shaded`, so it never clashes with the ByteBuddy that Hibernate brings, and
  `Nexus` injection is turned off because inlined advice does not need it.

## Units of work

`RequestTracer` keeps the current unit of work of each thread in a `ThreadLocal<ActiveRequest>`.

- **HTTP requests** start in `ServletAdvice` when a `REQUEST` dispatch enters the first `FilterChain.doFilter` or
  `HttpServlet.service` on the thread, and end when that call returns. Forwards, includes and nested filters land in
  the same request.
- **Tasks** start with the first query that runs outside a request. A task ends when its connection is closed or its
  transaction committed or rolled back, after a second without queries, or at 1000 queries, so a long batch splits
  into readable pieces. It is named after the outermost method of your packages that is on the stack, or the nearest
  application frame when there is no call tree.
- **Entry tasks** start and end with a method named by `entry=`, so a whole job is one unit however many connections
  it opens.

An `ActiveRequest` records its start in wall-clock and monotonic time, its thread ID, the number of classes the JVM had
loaded, its queries and its call tree. When it ends it becomes an immutable `RequestTrace`. Units that ran neither SQL
nor any of your methods are dropped.

## Tracing queries

- **One query, one record.** Pools wrap the driver's statements in their own, so `HikariProxyPreparedStatement.execute`
  calls `JdbcPreparedStatement.execute`. A per-unit call depth counter counts only the outermost call.
- **SQL of prepared statements.** `execute()` on a prepared statement carries no SQL. `PrepareStatementAdvice` stores
  the SQL of every statement it returns in a `StatementState`, and `ParameterAdvice` adds each value bound with
  `setXxx`, formatted by `ParameterValues`: strings quoted and cut at 64 characters, binary values as their size,
  `null` as `null`. The states live in a `WeakIdentityMap`, a map keyed by identity with weak references, so a
  statement the application drops is never kept alive by QueryScope.
- **Rows.** A `ResultSet` returned by a query goes into the same kind of map, pointing at the query's record, and
  `RowAdvice` counts every `next()` that returns `true`.
- **Call sites.** `StackWalker` finds the first frame outside the JDK, the agent, generated proxies and a list of known
  framework, pool, driver and logging packages, which is the line of application code that ran the query.

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
  frames of the same lambda form merge. Where Flight Recorder is missing, `ThreadDumpSampler` calls
  `Thread.getAllStackTraces()` every 20 ms or more and keeps runnable threads.
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

## The panel

**Server.** `PanelServer` is the JDK's built-in `com.sun.net.httpserver`, bound to loopback on daemon threads. It
serves the static panel and a JSON API: the stored reports, their call trees, profiles, and a server-sent events
stream with one event per finished report. Every SSE client gets its own bounded queue, so a slow browser never holds
up an application thread.

**Security.** The panel and the hub listen on loopback only and reject requests whose `Host` header is not
`localhost`, `127.0.0.1` or `[::1]`, which stops DNS rebinding. The hub's attach endpoint, the only one that changes
anything outside QueryScope, also requires a JSON content type and a same-origin `Origin` header, so a web page on
another site cannot trigger an attach from the user's browser.

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
| Samples                       | 15 minutes, at most 200,000                        |
| Session summary               | 500 endpoints, 200 distinct findings               |
| Statements and result sets    | held weakly, gone with the application's objects   |

QueryScope's threads are daemons, so they never keep the application alive: `queryscope-panel-*` for the panel,
`queryscope-flight-recorder` for the sampler, `queryscope-sampler-start` and `queryscope-panel-start` for startup, and
`queryscope-summary` as the shutdown hook. Samples taken on QueryScope's own threads are dropped.

## Overhead

Measured on the demo with 50 calls per endpoint after warm-up, the call tree and the sampler together changed the
average response time by less than a millisecond, within the noise of the measurement. Flight Recorder at 10 ms is
designed to stay around one percent of CPU. The call tree costs a few dozen nanoseconds per call of your methods,
which only shows in tight loops over tiny methods. Instrumenting classes costs time when they load, which is most
visible right after attaching to a large application.

## Testing

`./gradlew build` runs every test, from units to real JVMs:

- **Unit tests** for the SQL normalizer, the analyzer, the options, the launcher and hub command lines, parameter
  formatting, the session summary, the call stack, package detection, the sample store, profiles, the JSON writer, the
  report store and the panel and hub servers, including SSE, the `Host` check and the attach protection.
- **`SamplingTest`** runs the Flight Recorder sampler and the thread dump sampler against a busy thread.
- **`AgentInstrumentationTest`** installs the agent into the test JVM with ByteBuddyAgent and runs real queries
  through HikariCP and H2 inside simulated `jakarta.servlet` and `javax.servlet` requests, outside any request and
  inside an `entry=` method, and checks the call tree of a profiled fixture class with recursion and exceptions.
- **`QueryScopeEndToEndTest`** starts the demo shop with `-javaagent:queryscope.jar` in a separate JVM and checks every
  finding, the call tree and the Flight Recorder samples through the panel's API. The demo runs on the test classpath
  without the agent's own classes, so only the shaded jar is attached.
- **`LauncherEndToEndTest`** starts the demo through `java -jar queryscope.jar` and checks that killing the launcher
  takes the application and its summary down with it.
- **`AttachEndToEndTest`** starts the demo without QueryScope, starts the hub, attaches to the demo through the hub's
  API and checks that the agent finds the N+1 in the running application.
- **`PlainJavaEndToEndTest`** runs a plain `main` program on H2 with the agent and checks its task, the N+1, and that
  the program still exits and prints its summary.

GitHub Actions runs the same build on Temurin 25 for every push and pull request.

## Code layout

```
me.fertiz.queryscope.agent             QueryScopeAgent (premain, agentmain), AgentConfig, AgentInstaller
├── trace                              requests and tasks with their SQL: RequestTracer, ActiveRequest,
│                                      Servlets, the JDBC, servlet and entry advice, RequestTrace, QueryExecution
├── calltree                           the call tree of your methods: CallTracer, CallTreeBuilder, MethodAdvice,
│                                      CallSites, AppPackages, CallTree, CallNode
├── findings                           QueryAnalyzer, SqlNormalizer, Thresholds and the finding records
├── profiler                           Sampling, JfrSampler, ThreadDumpSampler, SampleStore, Profile
├── console                            ConsoleReporter, SessionSummary, FindingText
├── panel                              PanelServer, ReportStore, ReportJson, ProfileJson, ProfileQuery
├── hub                                HubCommand, HubServer, LocalProcesses, JavaProcess
├── http                               LocalServer, HttpSupport, JsonWriter, Browser, shared by panel and hub
└── launch                             QueryScopeLauncher, LaunchCommand
```

The web pages live in `src/main/resources/me/fertiz/queryscope/agent/web`. The tests mirror the packages, and `src/test` also holds the demo shop (`demo`), a plain JDBC program (`plain`), a
profiled fixture (`profiled`) and the end-to-end tests (`e2e`).
