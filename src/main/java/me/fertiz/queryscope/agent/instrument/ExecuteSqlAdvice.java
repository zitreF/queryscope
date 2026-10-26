package me.fertiz.queryscope.agent.instrument;

import me.fertiz.queryscope.agent.trace.RequestTracer;
import net.bytebuddy.asm.Advice;

public final class ExecuteSqlAdvice {

    private ExecuteSqlAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        RequestTracer.queryStarted();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Argument(0) String sql, @Advice.Thrown Throwable thrown) {
        RequestTracer.queryFinished(sql, thrown != null);
    }
}
