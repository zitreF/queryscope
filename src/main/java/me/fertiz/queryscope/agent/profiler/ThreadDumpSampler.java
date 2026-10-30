package me.fertiz.queryscope.agent.profiler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ThreadDumpSampler {

    static final String SOURCE = "thread dumps";

    private ThreadDumpSampler() {
    }

    static Runnable start(SampleStore store, Duration interval) {
        Thread sampler = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    sample(store);
                    Thread.sleep(interval.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Sampling.THREAD_NAME_PREFIX + "thread-dumps");
        sampler.setDaemon(true);
        sampler.start();
        return sampler::interrupt;
    }

    static void sample(SampleStore store) {
        long now = store.now();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            if (stack.length == 0 || thread == Thread.currentThread() || thread.getState() != Thread.State.RUNNABLE
                    || Sampling.isOwnThread(thread.getName())) {
                continue;
            }
            List<String> methods = new ArrayList<>(stack.length);
            for (int index = stack.length - 1; index >= 0; index--) {
                methods.add(Sampling.qualifiedMethod(stack[index].getClassName(), stack[index].getMethodName()));
            }
            store.add(now, thread.getId(), thread.getName(), !stack[0].isNativeMethod(), false, methods);
        }
    }
}
