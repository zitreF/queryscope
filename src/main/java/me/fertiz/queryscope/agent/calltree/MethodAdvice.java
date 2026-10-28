package me.fertiz.queryscope.agent.calltree;

import net.bytebuddy.asm.Advice;

public final class MethodAdvice {

    private MethodAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static int enter(@Advice.Origin("#t.#m") String method) {
        return CallTracer.methodEntered(method);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter int depth) {
        CallTracer.methodExited(depth);
    }
}
