package me.fertiz.queryscope.agent.profiler;

import java.time.Duration;

public final class Sampling {

    static final String THREAD_NAME_PREFIX = "queryscope-";

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final Duration SHORTEST_DUMP_INTERVAL = Duration.ofMillis(20);
    private static final long STOP_TIMEOUT_MILLIS = 2000;

    private volatile Runnable stopSampler = () -> {
    };

    private Sampling() {
    }

    public static Sampling start(SampleStore store, Duration interval) {
        Sampling sampling = new Sampling();
        Thread starter = new Thread(() -> sampling.startJfrOrThreadDumps(store, interval),
                THREAD_NAME_PREFIX + "sampler-start");
        starter.setDaemon(true);
        starter.start();
        return sampling;
    }

    public void stop() {
        Thread stopper = new Thread(stopSampler, THREAD_NAME_PREFIX + "sampler-stop");
        stopper.setDaemon(true);
        stopper.start();
        try {
            stopper.join(STOP_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String qualifiedMethod(String className, String method) {
        return withoutHiddenClassSuffix(className) + "." + method;
    }

    static boolean isOwnThread(String threadName) {
        return threadName != null && threadName.startsWith(THREAD_NAME_PREFIX);
    }

    static String withoutHiddenClassSuffix(String name) {
        int hidden = name.indexOf("/0x");
        if (hidden < 0) {
            hidden = name.indexOf(".0x");
        }
        return hidden < 0 ? name : name.substring(0, hidden);
    }

    private synchronized void startJfrOrThreadDumps(SampleStore store, Duration interval) {
        try {
            stopSampler = JfrSampler.start(store, interval, () -> startThreadDumps(store, interval));
            store.markStarted(JfrSampler.SOURCE, interval);
        } catch (LinkageError | RuntimeException e) {
            LOGGER.log(System.Logger.Level.INFO, "QueryScope falls back to thread dumps for sampling: " + e);
            startThreadDumps(store, interval);
        }
    }

    private synchronized void startThreadDumps(SampleStore store, Duration interval) {
        Duration dumpInterval = interval.compareTo(SHORTEST_DUMP_INTERVAL) < 0 ? SHORTEST_DUMP_INTERVAL : interval;
        stopSampler = ThreadDumpSampler.start(store, dumpInterval);
        store.markStarted(ThreadDumpSampler.SOURCE, dumpInterval);
    }
}
