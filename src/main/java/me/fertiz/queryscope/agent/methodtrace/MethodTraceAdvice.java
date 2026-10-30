package me.fertiz.queryscope.agent.methodtrace;

import net.bytebuddy.asm.Advice;

public final class MethodTraceAdvice {

    private MethodTraceAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Origin("#t.#m") String method, @Advice.Enter long startedNanos,
                            @Advice.Thrown Throwable thrown) {
        MethodTraces.called(method, startedNanos, thrown != null);
    }
}
