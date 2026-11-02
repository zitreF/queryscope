package me.fertiz.queryscope.agent.loadtest;

import me.fertiz.queryscope.agent.findings.Finding;
import me.fertiz.queryscope.agent.findings.FindingKind;
import me.fertiz.queryscope.agent.findings.FindingText;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.trace.UnitKind;
import me.fertiz.queryscope.agent.trace.UnitTrace;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class LoadTest {

    private static final long STILL_SENDING = -1;

    private final long id;
    private final LoadTestPlan plan;
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final AtomicInteger claimed = new AtomicInteger();
    private final long[] clientNanos;
    private final long[] serverNanos;
    private final Map<Integer, Integer> statuses = new TreeMap<>();
    private final Map<String, FindingTally> findings = new LinkedHashMap<>();
    private final Map<FindingKind, Integer> requestsByFindingKind = new EnumMap<>(FindingKind.class);
    private volatile boolean stopRequested;
    private LoadTestResult.State state = LoadTestResult.State.RUNNING;
    private long sendingNanos = STILL_SENDING;
    private int answered;
    private int failed;
    private String firstError;
    private int traced;
    private long queries;
    private int fewestQueries;
    private int mostQueries;
    private long databaseNanos;

    LoadTest(long id, LoadTestPlan plan) {
        this.id = id;
        this.plan = plan;
        this.clientNanos = new long[plan.requests()];
        this.serverNanos = new long[plan.requests()];
    }

    long id() {
        return id;
    }

    LoadTestPlan plan() {
        return plan;
    }

    synchronized boolean isRunning() {
        return state == LoadTestResult.State.RUNNING;
    }

    void sendRequests(RequestSender sender) {
        while (!stopRequested && claimed.getAndIncrement() < plan.requests()) {
            long started = System.nanoTime();
            try {
                int status = sender.send(plan, id);
                answered(status, System.nanoTime() - started);
            } catch (IOException | RuntimeException e) {
                failed(e);
            }
        }
    }

    synchronized boolean requestStop() {
        if (state != LoadTestResult.State.RUNNING) {
            return false;
        }
        stopRequested = true;
        return true;
    }

    synchronized void recordServerSide(UnitReport report) {
        UnitTrace trace = report.trace();
        if (trace.kind() != UnitKind.HTTP || traced == serverNanos.length) {
            return;
        }
        int count = trace.queryCount();
        fewestQueries = traced == 0 ? count : Math.min(fewestQueries, count);
        mostQueries = Math.max(mostQueries, count);
        serverNanos[traced++] = trace.durationNanos();
        queries += count;
        databaseNanos += trace.databaseNanos();
        Set<String> counted = new HashSet<>();
        Set<FindingKind> kinds = EnumSet.noneOf(FindingKind.class);
        for (Finding finding : report.findings()) {
            String key = FindingText.groupKey(finding, trace.endpoint());
            if (counted.add(key)) {
                findings.computeIfAbsent(key, ignored -> new FindingTally(finding.kind(),
                        FindingText.describeWithoutParameters(finding))).requests++;
            }
            kinds.add(finding.kind());
        }
        kinds.forEach(kind -> requestsByFindingKind.merge(kind, 1, Integer::sum));
        notifyAll();
    }

    synchronized void sendingEnded() {
        sendingNanos = System.nanoTime() - startedNanos;
    }

    synchronized void awaitServerSide(long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        long left = timeoutNanos;
        while (traced < answered && left > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, left);
            left = deadline - System.nanoTime();
        }
    }

    synchronized void finish() {
        state = stopRequested && answered + failed < plan.requests()
                ? LoadTestResult.State.STOPPED
                : LoadTestResult.State.FINISHED;
        notifyAll();
    }

    synchronized void awaitFinish(long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        long left = timeoutNanos;
        while (state == LoadTestResult.State.RUNNING && left > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, left);
            left = deadline - System.nanoTime();
        }
    }

    synchronized LoadTestResult result() {
        long elapsed = sendingNanos == STILL_SENDING ? System.nanoTime() - startedNanos : sendingNanos;
        return new LoadTestResult(id, plan, state, startedAt, elapsed, answered, failed, firstError,
                new TreeMap<>(statuses), LoadTestResult.Latency.of(clientNanos, answered), traced,
                LoadTestResult.Latency.of(serverNanos, traced), queries, fewestQueries, mostQueries, databaseNanos,
                findings.values().stream()
                        .sorted(Comparator.comparingInt((FindingTally tally) -> tally.requests).reversed())
                        .map(tally -> new LoadTestResult.FindingCount(tally.kind, tally.example, tally.requests))
                        .toList(),
                new EnumMap<>(requestsByFindingKind));
    }

    private synchronized void answered(int status, long nanos) {
        clientNanos[answered++] = nanos;
        statuses.merge(status, 1, Integer::sum);
    }

    private synchronized void failed(Exception e) {
        failed++;
        if (firstError == null) {
            firstError = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static final class FindingTally {

        private final FindingKind kind;
        private final String example;
        private int requests;

        private FindingTally(FindingKind kind, String example) {
            this.kind = kind;
            this.example = example;
        }
    }
}
