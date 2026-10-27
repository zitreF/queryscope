package me.fertiz.queryscope.agent.analysis;

import java.time.Duration;

public record Thresholds(int repeatedStatements, Duration slowQuery, int largeResultRows, int queriesPerRequest) {
}
