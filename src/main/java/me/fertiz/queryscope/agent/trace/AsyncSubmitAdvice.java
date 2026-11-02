package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.bootstrap.AsyncBridge;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class AsyncSubmitAdvice {

    private AsyncSubmitAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
            @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object task
    ) {
        task = AsyncBridge.submitted(task);
    }
}
