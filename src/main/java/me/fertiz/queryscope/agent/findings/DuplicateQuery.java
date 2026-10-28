package me.fertiz.queryscope.agent.findings;

import java.util.List;

public record DuplicateQuery(String normalizedSql, String parameters, String callSite, List<Integer> queryIndexes)
        implements Finding {

    public int executions() {
        return queryIndexes.size();
    }
}
