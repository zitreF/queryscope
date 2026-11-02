package me.fertiz.queryscope.agent.calltree;

public record AsyncCalls(CallTree tree, long durationNanos, int firstQueryIndex, int keptQueries) {

    int shift(int queryIndex) {
        return queryIndex < keptQueries ? firstQueryIndex + queryIndex : -1;
    }
}
