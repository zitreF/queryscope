package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.bootstrap.HttpServerBridge;
import net.bytebuddy.asm.Advice;

public final class JdkHttpServerAdvice {

    private JdkHttpServerAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.Argument(0) Object exchange) {
        return HttpServerBridge.requestStarted(exchange);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.Enter boolean started,
            @Advice.Argument(0) Object exchange,
            @Advice.Thrown Throwable thrown
    ) {
        if (started) {
            HttpServerBridge.requestFinished(exchange, thrown);
        }
    }
}
