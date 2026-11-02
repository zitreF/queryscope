package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.bootstrap.AsyncBridge;
import net.bytebuddy.asm.Advice;

public final class AsyncRunAdvice {

    private AsyncRunAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.This Object task) {
        return AsyncBridge.started(task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter boolean started, @Advice.This Object task) {
        if (started) {
            AsyncBridge.finished(task);
        }
    }
}
