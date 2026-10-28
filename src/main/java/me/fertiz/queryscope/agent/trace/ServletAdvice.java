package me.fertiz.queryscope.agent.trace;

import net.bytebuddy.asm.Advice;

public final class ServletAdvice {

    private ServletAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.Argument(0) Object request) {
        return Servlets.requestStarted(request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.Enter boolean started,
            @Advice.Argument(1) Object response,
            @Advice.Thrown Throwable thrown
    ) {
        if (started) {
            Servlets.requestFinished(response, thrown != null);
        }
    }
}
