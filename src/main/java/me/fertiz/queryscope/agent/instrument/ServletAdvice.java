package me.fertiz.queryscope.agent.instrument;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.fertiz.queryscope.agent.trace.RequestTracer;
import net.bytebuddy.asm.Advice;

public final class ServletAdvice {

    private ServletAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.Argument(0) ServletRequest request) {
        return request instanceof HttpServletRequest http
                && http.getDispatcherType() == DispatcherType.REQUEST
                && RequestTracer.requestStarted(http.getMethod(), http.getRequestURI(), http.getQueryString());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.Enter boolean started,
            @Advice.Argument(1) ServletResponse response,
            @Advice.Thrown Throwable thrown
    ) {
        if (started) {
            int status = 500;
            if (thrown == null && response instanceof HttpServletResponse http) {
                status = http.getStatus();
            }
            RequestTracer.requestFinished(status);
        }
    }
}
