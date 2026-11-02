package me.fertiz.queryscope.agent.trace;

import com.sun.net.httpserver.HttpExchange;
import me.fertiz.queryscope.agent.appcode.CallSites;

import java.net.URI;

public final class JdkHttpServerRequests {

    private JdkHttpServerRequests() {
    }

    public static boolean requestStarted(Object exchange) {
        if (!(exchange instanceof HttpExchange http)
                || CallSites.isQueryScopeThread(Thread.currentThread().getName())) {
            return false;
        }
        URI uri = http.getRequestURI();
        return UnitTracer.requestStarted(http.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(),
                http.getLocalAddress().getPort(), http.getRequestHeaders().getFirst(UnitTracer.LOAD_TEST_HEADER));
    }

    public static void requestFinished(Object exchange, Throwable thrown) {
        int sentStatus = ((HttpExchange) exchange).getResponseCode();
        UnitTracer.requestFinished(thrown != null ? Servlets.FAILED_STATUS
                : sentStatus > 0 ? sentStatus : Servlets.ASSUMED_STATUS);
    }
}
