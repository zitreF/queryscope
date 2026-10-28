package me.fertiz.queryscope.agent.profile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class ThreadDumpSampler {

    static final String SOURCE = "thread dumps";

    private ThreadDumpSampler() {
    }

    static Runnable start(SampleStore store, Duration interval) {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (running.get()) {
                sample(store);
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, Sampling.OWN_THREADS + "thread-dumps");
        sampler.setDaemon(true);
        sampler.start();
        return () -> running.set(false);
    }

    static void sample(SampleStore store) {
        long now = store.now();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            if (stack.length == 0 || thread == Thread.currentThread() || thread.getState() != Thread.State.RUNNABLE
                    || thread.getName().startsWith(Sampling.OWN_THREADS)) {
                continue;
            }
            List<String> methods = new ArrayList<>(stack.length);
            for (int index = stack.length - 1; index >= 0; index--) {
                methods.add(Sampling.typeName(stack[index].getClassName()) + "." + stack[index].getMethodName());
            }
            store.add(now, thread.getId(), thread.getName(), !stack[0].isNativeMethod(), false, methods);
        }
    }
}
