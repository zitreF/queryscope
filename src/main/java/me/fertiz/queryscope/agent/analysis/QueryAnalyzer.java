package me.fertiz.queryscope.agent.analysis;

import me.fertiz.queryscope.agent.data.Finding;
import me.fertiz.queryscope.agent.data.NPlusOne;
import me.fertiz.queryscope.agent.data.QueryExecution;
import me.fertiz.queryscope.agent.data.RequestReport;
import me.fertiz.queryscope.agent.data.RequestTrace;
import me.fertiz.queryscope.agent.data.SlowQuery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class QueryAnalyzer {

    private final int nPlusOneThreshold;
    private final long slowQueryNanos;

    public QueryAnalyzer(int nPlusOneThreshold, Duration slowQueryThreshold) {
        this.nPlusOneThreshold = nPlusOneThreshold;
        this.slowQueryNanos = slowQueryThreshold.toNanos();
    }

    public RequestReport analyze(RequestTrace trace) {
        List<Finding> findings = new ArrayList<>(repeatedSelects(trace.queries()));
        findings.addAll(slowQueries(trace.queries()));
        return new RequestReport(trace, List.copyOf(findings));
    }

    private List<NPlusOne> repeatedSelects(List<QueryExecution> queries) {
        Map<String, List<Integer>> indexesBySql = new LinkedHashMap<>();
        for (int index = 0; index < queries.size(); index++) {
            String normalized = SqlNormalizer.normalize(queries.get(index).sql());
            if (normalized.startsWith("select") || normalized.startsWith("with")) {
                indexesBySql.computeIfAbsent(normalized, sql -> new ArrayList<>()).add(index);
            }
        }
        return indexesBySql.entrySet().stream()
                .filter(group -> group.getValue().size() >= nPlusOneThreshold)
                .map(group -> nPlusOne(group.getKey(), group.getValue(), queries))
                .sorted(Comparator.comparingInt(NPlusOne::executions).reversed())
                .toList();
    }

    private static NPlusOne nPlusOne(String normalizedSql, List<Integer> indexes, List<QueryExecution> queries) {
        List<QueryExecution> group = indexes.stream().map(queries::get).toList();
        long totalNanos = group.stream().mapToLong(QueryExecution::durationNanos).sum();
        return new NPlusOne(normalizedSql, mostCommonCallSite(group), List.copyOf(indexes), totalNanos);
    }

    private static String mostCommonCallSite(List<QueryExecution> group) {
        return group.stream()
                .map(QueryExecution::callSite)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private List<SlowQuery> slowQueries(List<QueryExecution> queries) {
        List<SlowQuery> slow = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            if (queries.get(index).durationNanos() >= slowQueryNanos) {
                slow.add(new SlowQuery(index, queries.get(index)));
            }
        }
        return slow;
    }
}
