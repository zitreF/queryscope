package me.fertiz.queryscope.agent.bootstrap;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

public final class HttpServerBridge {

    private static volatile Predicate<Object> onRequestStarted;
    private static volatile BiConsumer<Object, Throwable> onRequestFinished;

    private HttpServerBridge() {
    }

    public static void connect(Predicate<Object> requestStarted, BiConsumer<Object, Throwable> requestFinished) {
        onRequestStarted = requestStarted;
        onRequestFinished = requestFinished;
    }

    public static boolean requestStarted(Object exchange) {
        Predicate<Object> listener = onRequestStarted;
        return listener != null && listener.test(exchange);
    }

    public static void requestFinished(Object exchange, Throwable thrown) {
        BiConsumer<Object, Throwable> listener = onRequestFinished;
        if (listener != null) {
            listener.accept(exchange, thrown);
        }
    }
}
