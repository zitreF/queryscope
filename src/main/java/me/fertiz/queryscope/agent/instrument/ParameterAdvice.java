package me.fertiz.queryscope.agent.instrument;

import me.fertiz.queryscope.agent.trace.RequestTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class ParameterAdvice {

    private ParameterAdvice() {
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
            @Advice.This Object statement,
            @Advice.Origin("#m") String setter,
            @Advice.Argument(0) int index,
            @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value
    ) {
        RequestTracer.parameterSet(statement, setter, index, value);
    }
}
