package me.fertiz.queryscope.agent.trace;

import net.bytebuddy.asm.Advice;

public final class RowAdvice {

    private RowAdvice() {
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object resultSet, @Advice.Return boolean hasRow) {
        if (hasRow) {
            RequestTracer.rowRead(resultSet);
        }
    }
}
