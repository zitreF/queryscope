package me.fertiz.queryscope.agent.analysis;

import me.fertiz.queryscope.agent.data.CallNode;
import me.fertiz.queryscope.agent.data.DuplicateQuery;
import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.LargeResult;
import me.fertiz.queryscope.agent.data.NPlusOne;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RepeatedWrite;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.SlowMethod;
import me.fertiz.queryscope.agent.data.SlowQuery;
import me.fertiz.queryscope.agent.data.TooManyQueries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class QueryAnalyzer {

    private static final List<String> READS = List.of("select", "with");
    private static final List<String> WRITES = List.of("insert", "update", "delete", "merge", "upsert", "replace");
    private static final int DUPLICATE_THRESHOLD = 2;
    private static final int MAX_SLOW_METHODS = 3;

    private final Thresholds thresholds;

    public QueryAnalyzer(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public RequestReport analyze(RequestTrace trace) {
        List<QueryExecution> queries = trace.queries();
        Map<String, List<Integer>> reads = new LinkedHashMap<>();
        Map<String, List<Integer>> writes = new LinkedHashMap<>();
        Map<String, List<Integer>> identicalReads = new LinkedHashMap<>();
        for (int index = 0; index < queries.size(); index++) {
            QueryExecution query = queries.get(index);
            String normalized = SqlNormalizer.normalize(query.sql());
            if (startsWithAny(normalized, READS)) {
                reads.computeIfAbsent(normalized, sql -> new ArrayList<>()).add(index);
                identicalReads.computeIfAbsent(query.sql() + '\u0000' + query.parameters(), sql -> new ArrayList<>())
                        .add(index);
            } else if (startsWithAny(normalized, WRITES)) {
                writes.computeIfAbsent(normalized, sql -> new ArrayList<>()).add(index);
            }
        }

        List<NPlusOne> nPlusOnes = repeated(reads, thresholds.repeatedStatements()).stream()
                .map(indexes -> new NPlusOne(normalizedSql(queries, indexes), mostCommonCallSite(queries, indexes),
                        indexes, totalNanos(queries, indexes)))
                .toList();
        List<Finding> findings = new ArrayList<>(nPlusOnes);
        repeated(writes, thresholds.repeatedStatements()).stream()
                .map(indexes -> new RepeatedWrite(normalizedSql(queries, indexes), mostCommonCallSite(queries, indexes),
                        indexes, totalNanos(queries, indexes)))
                .forEach(findings::add);
        repeated(identicalReads, DUPLICATE_THRESHOLD).stream()
                .filter(indexes -> nPlusOnes.stream().noneMatch(nPlusOne -> nPlusOne.queryIndexes().containsAll(indexes)))
                .map(indexes -> {
                    QueryExecution first = queries.get(indexes.get(0));
                    return new DuplicateQuery(first.sql(), first.parameters(), mostCommonCallSite(queries, indexes), indexes);
                })
                .forEach(findings::add);
        long slowNanos = thresholds.slowQuery().toNanos();
        for (int index = 0; index < queries.size(); index++) {
            if (queries.get(index).durationNanos() >= slowNanos) {
                findings.add(new SlowQuery(index, queries.get(index)));
            }
        }
        for (int index = 0; index < queries.size(); index++) {
            if (queries.get(index).rows() >= thresholds.largeResultRows()) {
                findings.add(new LargeResult(index, queries.get(index)));
            }
        }
        if (!trace.isWarmUp()) {
            findings.addAll(slowMethods(trace));
        }
        if (queries.size() >= thresholds.queriesPerRequest()) {
            long databaseNanos = queries.stream().mapToLong(QueryExecution::durationNanos).sum();
            findings.add(new TooManyQueries(queries.size(), thresholds.queriesPerRequest(), databaseNanos,
                    trace.durationNanos()));
        }
        return new RequestReport(trace, List.copyOf(findings));
    }

    private List<SlowMethod> slowMethods(RequestTrace trace) {
        Map<String, OwnTime> methods = new LinkedHashMap<>();
        collectOwnTime(trace.callTree().calls(), trace.queries(), methods);
        long thresholdNanos = thresholds.slowMethod().toNanos();
        return methods.entrySet().stream()
                .filter(entry -> entry.getValue().nanos() >= thresholdNanos)
                .sorted(Comparator.comparingLong((Map.Entry<String, OwnTime> entry) -> entry.getValue().nanos()).reversed())
                .limit(MAX_SLOW_METHODS)
                .map(entry -> new SlowMethod(entry.getKey(), entry.getValue().calls(), entry.getValue().nanos(),
                        trace.durationNanos()))
                .toList();
    }

    private static void collectOwnTime(List<CallNode> nodes, List<QueryExecution> queries, Map<String, OwnTime> methods) {
        for (CallNode node : nodes) {
            if (!node.startedBeforeUnit()) {
                long sqlNanos = totalNanos(queries, node.queryIndexes());
                methods.merge(node.method(), new OwnTime(node.calls(), Math.max(0, node.selfNanos() - sqlNanos)),
                        OwnTime::plus);
            }
            collectOwnTime(node.children(), queries, methods);
        }
    }

    private static List<List<Integer>> repeated(Map<String, List<Integer>> groups, int threshold) {
        return groups.values().stream()
                .filter(indexes -> indexes.size() >= threshold)
                .sorted(Comparator.comparingInt(List<Integer>::size).reversed())
                .map(List::copyOf)
                .toList();
    }

    private static boolean startsWithAny(String sql, List<String> keywords) {
        return keywords.stream().anyMatch(sql::startsWith);
    }

    private static String normalizedSql(List<QueryExecution> queries, List<Integer> indexes) {
        return SqlNormalizer.normalize(queries.get(indexes.get(0)).sql());
    }

    private static long totalNanos(List<QueryExecution> queries, List<Integer> indexes) {
        return indexes.stream().mapToLong(index -> queries.get(index).durationNanos()).sum();
    }

    private static String mostCommonCallSite(List<QueryExecution> queries, List<Integer> indexes) {
        return indexes.stream()
                .map(index -> queries.get(index).callSite())
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private record OwnTime(long calls, long nanos) {

        private OwnTime plus(OwnTime other) {
            return new OwnTime(calls + other.calls, nanos + other.nanos);
        }
    }
}
