package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.bootstrap.AsyncBridge;
import net.bytebuddy.asm.Advice;

public final class AsyncForkAdvice {

    private AsyncForkAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object task) {
        AsyncBridge.submitted(task);
    }
}
