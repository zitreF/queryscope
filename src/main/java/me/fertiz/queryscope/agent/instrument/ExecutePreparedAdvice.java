package me.fertiz.queryscope.agent.instrument;

import me.fertiz.queryscope.agent.trace.RequestTracer;
import net.bytebuddy.asm.Advice;

public final class ExecutePreparedAdvice {

    private ExecutePreparedAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        RequestTracer.queryStarted();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.This Object statement, @Advice.Thrown Throwable thrown) {
        RequestTracer.preparedQueryFinished(statement, thrown != null);
    }
}
