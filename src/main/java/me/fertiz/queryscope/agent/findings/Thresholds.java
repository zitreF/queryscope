package me.fertiz.queryscope.agent.findings;

import java.time.Duration;

public record Thresholds(
        int repeatedStatements,
        Duration slowQuery,
        int largeResultRows,
        int maxQueries,
        Duration slowMethod
) {
}
