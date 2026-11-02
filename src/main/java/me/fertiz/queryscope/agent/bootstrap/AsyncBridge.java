package me.fertiz.queryscope.agent.bootstrap;

import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.FutureTask;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class AsyncBridge {

    private static volatile Hooks hooks;

    private AsyncBridge() {
    }

    public static void connect(Supplier<Object> ownerOfThisThread, BiConsumer<Object, Object> submitted,
                               Predicate<Object> started, Consumer<Object> finished) {
        hooks = new Hooks(ownerOfThisThread, submitted, started, finished);
    }

    public static Object submitted(Object task) {
        Hooks current = hooks;
        if (current == null || task == null || task instanceof TrackedRunnable || task instanceof TrackedCallable<?>) {
            return task;
        }
        Object owner = current.ownerOfThisThread().get();
        if (owner == null) {
            return task;
        }
        Object scheduled;
        if (task instanceof FutureTask<?> || task instanceof ForkJoinTask<?>) {
            scheduled = task;
        } else if (task instanceof Runnable runnable) {
            scheduled = new TrackedRunnable(runnable);
        } else if (task instanceof Callable<?> callable) {
            scheduled = new TrackedCallable<>(callable);
        } else {
            return task;
        }
        current.submitted().accept(scheduled, owner);
        return scheduled;
    }

    public static boolean started(Object task) {
        Hooks current = hooks;
        return current != null && task != null && current.started().test(task);
    }

    public static void finished(Object task) {
        Hooks current = hooks;
        if (current != null) {
            current.finished().accept(task);
        }
    }

    private record Hooks(Supplier<Object> ownerOfThisThread, BiConsumer<Object, Object> submitted,
                         Predicate<Object> started, Consumer<Object> finished) {
    }

    public static final class TrackedRunnable implements Runnable {

        private final Runnable task;

        private TrackedRunnable(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            boolean started = started(this);
            try {
                task.run();
            } finally {
                if (started) {
                    finished(this);
                }
            }
        }

        @Override
        public String toString() {
            return task.toString();
        }
    }

    public static final class TrackedCallable<V> implements Callable<V> {

        private final Callable<V> task;

        private TrackedCallable(Callable<V> task) {
            this.task = task;
        }

        @Override
        public V call() throws Exception {
            boolean started = started(this);
            try {
                return task.call();
            } finally {
                if (started) {
                    finished(this);
                }
            }
        }

        @Override
        public String toString() {
            return task.toString();
        }
    }
}
