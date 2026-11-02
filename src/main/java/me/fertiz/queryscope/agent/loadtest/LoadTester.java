package me.fertiz.queryscope.agent.loadtest;

import me.fertiz.queryscope.agent.appcode.CallSites;
import me.fertiz.queryscope.agent.findings.UnitReport;
import me.fertiz.queryscope.agent.trace.UnitTrace;
import me.fertiz.queryscope.agent.trace.UnitTracer;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class LoadTester {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final String THREAD_NAME = CallSites.THREAD_NAME_PREFIX + "load-test-";
    private static final long SERVER_SIDE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final RequestSender sender;
    private final Consumer<LoadTestResult> onFinish;
    private final long serverSideWaitNanos;
    private final AtomicLong runs = new AtomicLong();
    private volatile LoadTest latest;

    public LoadTester() {
        this(LoopbackHttp::send, result -> LOGGER.log(System.Logger.Level.INFO, result.summary()),
                SERVER_SIDE_WAIT_NANOS);
    }

    LoadTester(RequestSender sender, Consumer<LoadTestResult> onFinish, long serverSideWaitNanos) {
        this.sender = sender;
        this.onFinish = onFinish;
        this.serverSideWaitNanos = serverSideWaitNanos;
    }

    public synchronized LoadTestResult start(LoadTestPlan plan) {
        LoadTest running = latest;
        if (running != null && running.isRunning()) {
            throw new IllegalStateException("QueryScope is still load testing " + running.plan().endpoint()
                    + ", stop that test first");
        }
        LoadTest test = new LoadTest(runs.incrementAndGet(), plan);
        latest = test;
        AtomicInteger working = new AtomicInteger(plan.workers());
        for (int worker = 1; worker <= plan.workers(); worker++) {
            Thread thread = new Thread(() -> sendAndFinish(test, working), THREAD_NAME + test.id() + "-" + worker);
            thread.setDaemon(true);
            thread.start();
        }
        return test.result();
    }

    public boolean stop() {
        LoadTest test = latest;
        return test != null && test.requestStop();
    }

    public Optional<LoadTestResult> latest() {
        LoadTest test = latest;
        return test == null ? Optional.empty() : Optional.of(test.result());
    }

    public Optional<LoadTestResult> awaitLatest(Duration timeout) throws InterruptedException {
        LoadTest test = latest;
        if (test == null) {
            return Optional.empty();
        }
        test.awaitFinish(timeout.toNanos());
        return Optional.of(test.result());
    }

    public boolean sent(UnitTrace trace) {
        long run = trace.loadTestRun();
        return run != UnitTracer.NO_LOAD_TEST && run <= runs.get();
    }

    public void record(UnitReport report) {
        LoadTest test = latest;
        if (test != null && test.id() == report.trace().loadTestRun()) {
            test.recordServerSide(report);
        }
    }

    private void sendAndFinish(LoadTest test, AtomicInteger working) {
        try {
            test.sendRequests(sender);
        } finally {
            if (working.decrementAndGet() == 0) {
                finish(test);
            }
        }
    }

    private void finish(LoadTest test) {
        test.sendingEnded();
        try {
            test.awaitServerSide(serverSideWaitNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        test.finish();
        onFinish.accept(test.result());
    }
}
