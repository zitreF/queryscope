package me.fertiz.queryscope.agent.trace;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class ExecutePreparedAdvice {

    private ExecutePreparedAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
        RequestTracer.queryStarted();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.This Object statement,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
            @Advice.Thrown Throwable thrown
    ) {
        RequestTracer.preparedQueryFinished(statement, thrown != null, result);
    }
}
