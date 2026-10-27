package me.fertiz.queryscope.agent.data;

import java.util.List;

public record DuplicateQuery(String sql, String parameters, String callSite, List<Integer> queryIndexes)
        implements Finding {

    public int executions() {
        return queryIndexes.size();
    }
}
