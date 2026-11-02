package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.appcode.CallSites;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class AsyncWait {

    static final long WAIT_MILLIS = 1000;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, CallSites.THREAD_NAME_PREFIX + "async-wait");
        thread.setDaemon(true);
        return thread;
    });

    private AsyncWait() {
    }

    static void later(Runnable action) {
        TIMER.schedule(action, WAIT_MILLIS, TimeUnit.MILLISECONDS);
    }
}
