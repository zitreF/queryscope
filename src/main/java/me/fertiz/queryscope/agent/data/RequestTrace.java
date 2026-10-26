package me.fertiz.queryscope.agent.data;

import java.time.Instant;
import java.util.List;

public record RequestTrace(
        long id,
        String method,
        String path,
        int status,
        Instant startedAt,
        long durationNanos,
        List<QueryExecution> queries
) {
}
