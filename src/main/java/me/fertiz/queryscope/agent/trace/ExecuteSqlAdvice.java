package me.fertiz.queryscope.agent.trace;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class ExecuteSqlAdvice {

    private ExecuteSqlAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        UnitTracer.queryStarted();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.Argument(0) String sql,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
            @Advice.Thrown Throwable thrown
    ) {
        UnitTracer.queryFinished(sql, thrown != null, result);
    }
}
