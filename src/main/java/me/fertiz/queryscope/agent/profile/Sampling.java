package me.fertiz.queryscope.agent.profile;

import java.time.Duration;

public final class Sampling {

    static final String OWN_THREADS = "queryscope-";

    private static final System.Logger LOGGER = System.getLogger("QueryScope");
    private static final Duration SHORTEST_DUMP_INTERVAL = Duration.ofMillis(20);
    private static final long STOP_TIMEOUT_MILLIS = 2000;

    private volatile Runnable stop = () -> {
    };

    private Sampling() {
    }

    public static Sampling start(SampleStore store, Duration interval) {
        Sampling sampling = new Sampling();
        Thread starter = new Thread(() -> sampling.begin(store, interval), OWN_THREADS + "sampler-start");
        starter.setDaemon(true);
        starter.start();
        return sampling;
    }

    public void stop() {
        Thread stopper = new Thread(stop, OWN_THREADS + "sampler-stop");
        stopper.setDaemon(true);
        stopper.start();
        try {
            stopper.join(STOP_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String typeName(String name) {
        int hidden = name.indexOf("/0x");
        if (hidden < 0) {
            hidden = name.indexOf(".0x");
        }
        return hidden < 0 ? name : name.substring(0, hidden);
    }

    private void begin(SampleStore store, Duration interval) {
        try {
            stop = JfrSampler.start(store, interval);
            store.started(JfrSampler.SOURCE, interval);
            return;
        } catch (LinkageError | RuntimeException e) {
            LOGGER.log(System.Logger.Level.INFO, "QueryScope falls back to thread dumps for sampling: " + e);
        }
        Duration dumpInterval = interval.compareTo(SHORTEST_DUMP_INTERVAL) < 0 ? SHORTEST_DUMP_INTERVAL : interval;
        stop = ThreadDumpSampler.start(store, dumpInterval);
        store.started(ThreadDumpSampler.SOURCE, dumpInterval);
    }
}
