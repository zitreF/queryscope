package me.fertiz.queryscope.agent.loadtest;

import me.fertiz.queryscope.agent.findings.FindingKind;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record LoadTestResult(
        long id,
        LoadTestPlan plan,
        State state,
        Instant startedAt,
        long elapsedNanos,
        int answered,
        int failed,
        String firstError,
        Map<Integer, Integer> statuses,
        Latency client,
        int tracedRequests,
        Latency server,
        long queries,
        int fewestQueries,
        int mostQueries,
        long databaseNanos,
        List<FindingCount> findings,
        Map<FindingKind, Integer> requestsByFindingKind
) {

    private static final int FIRST_ERROR_STATUS = 400;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    public int done() {
        return answered + failed;
    }

    public double requestsPerSecond() {
        return elapsedNanos == 0 ? 0 : answered * NANOS_PER_SECOND / elapsedNanos;
    }

    public double averageQueries() {
        return tracedRequests == 0 ? 0 : (double) queries / tracedRequests;
    }

    public long averageDatabaseNanos() {
        return tracedRequests == 0 ? 0 : databaseNanos / tracedRequests;
    }

    public int errorStatuses() {
        return statuses.entrySet().stream()
                .filter(status -> status.getKey() >= FIRST_ERROR_STATUS)
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    public String summary() {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "QueryScope load test of %s %s: %d of %d requests answered in %.2f s (%.1f per second),"
                        + " p50 %.1f ms, p95 %.1f ms, p99 %.1f ms",
                plan.endpoint(), state == State.STOPPED ? "stopped" : "finished", answered, plan.requests(),
                elapsedNanos / NANOS_PER_SECOND, requestsPerSecond(), client.p50Nanos() / NANOS_PER_MILLI,
                client.p95Nanos() / NANOS_PER_MILLI, client.p99Nanos() / NANOS_PER_MILLI));
        if (tracedRequests > 0) {
            text.append(String.format(Locale.ROOT, ", %.1f queries per request", averageQueries()));
        }
        if (errorStatuses() > 0) {
            text.append(", ").append(errorStatuses()).append(" answered with an error status");
        }
        if (failed > 0) {
            text.append(", ").append(failed).append(" got no answer: ").append(firstError);
        }
        return text.toString();
    }

    public enum State {
        RUNNING,
        FINISHED,
        STOPPED
    }

    public record Latency(long averageNanos, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos) {

        static final Latency NONE = new Latency(0, 0, 0, 0, 0);

        static Latency of(long[] nanos, int count) {
            if (count == 0) {
                return NONE;
            }
            long[] sorted = Arrays.copyOf(nanos, count);
            Arrays.sort(sorted);
            long total = 0;
            for (long value : sorted) {
                total += value;
            }
            return new Latency(total / count, percentile(sorted, 0.50), percentile(sorted, 0.95),
                    percentile(sorted, 0.99), sorted[count - 1]);
        }

        private static long percentile(long[] sorted, double fraction) {
            int rank = (int) Math.ceil(fraction * sorted.length);
            return sorted[Math.max(1, rank) - 1];
        }
    }

    public record FindingCount(FindingKind kind, String example, int requests) {
    }
}
