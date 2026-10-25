package me.fertiz.queryscope.agent.report;

import me.fertiz.queryscope.agent.data.RequestTrace;

import java.util.Locale;
import java.util.function.Consumer;

public final class ConsoleReporter implements Consumer<RequestTrace> {

    private static final System.Logger LOGGER = System.getLogger("QueryScope");

    @Override
    public void accept(RequestTrace trace) {
        if (trace.queries().isEmpty()) {
            return;
        }
        LOGGER.log(System.Logger.Level.INFO, () -> String.format(Locale.ROOT, "%s %s -> %d, %d queries in %.1f ms",
                trace.method(), trace.path(), trace.status(), trace.queries().size(), trace.durationNanos() / 1e6));
    }
}
