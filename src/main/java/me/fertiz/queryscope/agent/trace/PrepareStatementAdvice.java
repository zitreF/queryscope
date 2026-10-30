package me.fertiz.queryscope.agent.trace;

import net.bytebuddy.asm.Advice;

public final class PrepareStatementAdvice {

    private PrepareStatementAdvice() {
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Argument(0) String sql, @Advice.Return Object statement) {
        UnitTracer.statementPrepared(statement, sql);
    }
}
