package me.fertiz.queryscope.agent.data;

import java.time.Instant;
import java.util.List;

public record RequestTrace(
        long id,
        UnitKind kind,
        String method,
        String path,
        int status,
        Instant startedAt,
        long durationNanos,
        List<QueryExecution> queries,
        CallTree callTree,
        long classesLoaded
) {

    public static final int WARM_UP_CLASSES = 50;

    public RequestTrace(long id, UnitKind kind, String method, String path, int status, Instant startedAt,
                        long durationNanos, List<QueryExecution> queries) {
        this(id, kind, method, path, status, startedAt, durationNanos, queries, CallTree.EMPTY, 0);
    }

    public boolean isWarmUp() {
        return classesLoaded >= WARM_UP_CLASSES;
    }
}
