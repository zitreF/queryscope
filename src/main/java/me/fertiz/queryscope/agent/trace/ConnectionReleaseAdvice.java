package me.fertiz.queryscope.agent.trace;

import net.bytebuddy.asm.Advice;

public final class ConnectionReleaseAdvice {

    private ConnectionReleaseAdvice() {
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit() {
        RequestTracer.connectionReleased();
    }
}
