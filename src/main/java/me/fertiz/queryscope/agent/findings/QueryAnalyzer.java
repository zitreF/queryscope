package me.fertiz.queryscope.agent.findings;

import me.fertiz.queryscope.agent.calltree.CallNode;
import me.fertiz.queryscope.agent.trace.QueryExecution;
import me.fertiz.queryscope.agent.trace.RequestTrace;

import java.util.ArrayList;
import java.util.Collection;
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
        List<String> statements = queries.stream().map(query -> SqlNormalizer.normalize(query.sql())).toList();
        Map<String, List<Integer>> reads = byStatement(statements, READS);
        List<Finding> findings = new ArrayList<>();
        findings.addAll(nPlusOnes(queries, statements, reads));
        findings.addAll(repeatedWrites(queries, statements));
        findings.addAll(duplicates(queries, statements, reads));
        findings.addAll(slowQueries(queries, statements));
        findings.addAll(largeResults(queries, statements));
        if (!trace.isWarmUp()) {
            findings.addAll(slowMethods(trace));
        }
        if (queries.size() >= thresholds.maxQueries()) {
            findings.add(new TooManyQueries(queries.size(), thresholds.maxQueries(), trace.databaseNanos(),
                    trace.durationNanos()));
        }
        return new RequestReport(trace, List.copyOf(findings));
    }

    private List<NPlusOne> nPlusOnes(List<QueryExecution> queries, List<String> statements,
                                     Map<String, List<Integer>> reads) {
        return atLeast(reads.values(), thresholds.repeatedStatements()).stream()
                .map(indexes -> new NPlusOne(statements.get(indexes.get(0)), mostCommonCallSite(queries, indexes),
                        indexes, totalNanos(queries, indexes)))
                .toList();
    }

    private List<RepeatedWrite> repeatedWrites(List<QueryExecution> queries, List<String> statements) {
        return atLeast(byStatement(statements, WRITES).values(), thresholds.repeatedStatements()).stream()
                .map(indexes -> new RepeatedWrite(statements.get(indexes.get(0)), mostCommonCallSite(queries, indexes),
                        indexes, totalNanos(queries, indexes)))
                .toList();
    }

    private List<DuplicateQuery> duplicates(List<QueryExecution> queries, List<String> statements,
                                            Map<String, List<Integer>> reads) {
        Map<List<String>, List<Integer>> identical = new LinkedHashMap<>();
        for (int index = 0; index < queries.size(); index++) {
            List<Integer> sameStatement = reads.get(statements.get(index));
            if (sameStatement != null && sameStatement.size() < thresholds.repeatedStatements()) {
                QueryExecution query = queries.get(index);
                identical.computeIfAbsent(List.of(query.sql(), query.parameters()), key -> new ArrayList<>()).add(index);
            }
        }
        return atLeast(identical.values(), DUPLICATE_THRESHOLD).stream()
                .map(indexes -> new DuplicateQuery(statements.get(indexes.get(0)),
                        queries.get(indexes.get(0)).parameters(), mostCommonCallSite(queries, indexes), indexes))
                .toList();
    }

    private List<SlowQuery> slowQueries(List<QueryExecution> queries, List<String> statements) {
        long slowNanos = thresholds.slowQuery().toNanos();
        List<SlowQuery> slow = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            if (queries.get(index).durationNanos() >= slowNanos) {
                slow.add(new SlowQuery(index, statements.get(index), queries.get(index)));
            }
        }
        return slow;
    }

    private List<LargeResult> largeResults(List<QueryExecution> queries, List<String> statements) {
        List<LargeResult> large = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            if (queries.get(index).rows() >= thresholds.largeResultRows()) {
                large.add(new LargeResult(index, statements.get(index), queries.get(index)));
            }
        }
        return large;
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

    private static Map<String, List<Integer>> byStatement(List<String> statements, List<String> keywords) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int index = 0; index < statements.size(); index++) {
            String statement = statements.get(index);
            if (keywords.stream().anyMatch(statement::startsWith)) {
                groups.computeIfAbsent(statement, key -> new ArrayList<>()).add(index);
            }
        }
        return groups;
    }

    private static List<List<Integer>> atLeast(Collection<List<Integer>> groups, int threshold) {
        return groups.stream()
                .filter(indexes -> indexes.size() >= threshold)
                .sorted(Comparator.comparingInt(List<Integer>::size).reversed())
                .map(List::copyOf)
                .toList();
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
