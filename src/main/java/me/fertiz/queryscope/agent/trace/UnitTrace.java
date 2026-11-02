package me.fertiz.queryscope.agent.trace;

import me.fertiz.queryscope.agent.calltree.CallTree;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

public record UnitTrace(
        long id,
        UnitKind kind,
        String method,
        String path,
        int status,
        Instant startedAt,
        long durationNanos,
        List<QueryExecution> queries,
        CallTree callTree,
        long classesLoaded,
        long threadId,
        int omittedQueries,
        long omittedNanos,
        int localPort,
        long loadTestRun
) {

    public static final int WARM_UP_CLASSES = 50;

    private static final Pattern UUID_SEGMENT =
            Pattern.compile("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/|$)");
    private static final Pattern NUMBER_SEGMENT = Pattern.compile("/\\d+(?=/|$)");

    public UnitTrace(long id, UnitKind kind, String method, String path, int status, Instant startedAt,
                        long durationNanos, List<QueryExecution> queries) {
        this(id, kind, method, path, status, startedAt, durationNanos, queries, CallTree.EMPTY, 0, 0, 0, 0, 0, 0);
    }

    public boolean isWarmUp() {
        return classesLoaded >= WARM_UP_CLASSES;
    }

    public int queryCount() {
        return queries.size() + omittedQueries;
    }

    public long databaseNanos() {
        return queries.stream().mapToLong(QueryExecution::durationNanos).sum() + omittedNanos;
    }

    public Instant endedAt() {
        return startedAt.plusNanos(durationNanos);
    }

    public String endpoint() {
        if (kind == UnitKind.TASK) {
            return "Task " + path;
        }
        String route = path.split("\\?", 2)[0];
        route = UUID_SEGMENT.matcher(route).replaceAll("/{uuid}");
        route = NUMBER_SEGMENT.matcher(route).replaceAll("/{id}");
        return method + " " + route;
    }
}
