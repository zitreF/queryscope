package me.fertiz.queryscope.agent.trace;

import net.bytebuddy.asm.Advice;

public final class EntryAdvice {

    private EntryAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.Origin("#t.#m") String method) {
        return UnitTracer.taskStarted(method);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter boolean started) {
        if (started) {
            UnitTracer.taskFinished();
        }
    }
}
