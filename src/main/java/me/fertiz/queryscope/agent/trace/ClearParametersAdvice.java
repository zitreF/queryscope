package me.fertiz.queryscope.agent.trace;

import net.bytebuddy.asm.Advice;

public final class ClearParametersAdvice {

    private ClearParametersAdvice() {
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object statement) {
        RequestTracer.parametersCleared(statement);
    }
}
