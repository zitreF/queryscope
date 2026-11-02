package me.fertiz.queryscope.agent.console;

import me.fertiz.queryscope.agent.findings.FindingText;
import me.fertiz.queryscope.agent.findings.UnitReport;

import java.util.LinkedHashSet;
import java.util.Set;

final class EndpointStats {

    final String name;
    final Set<String> badges = new LinkedHashSet<>();
    long calls;
    long queries;
    long durationNanos;
    long databaseNanos;

    EndpointStats(String name) {
        this.name = name;
    }

    void add(UnitReport report) {
        calls++;
        queries += report.trace().queryCount();
        durationNanos += report.trace().durationNanos();
        databaseNanos += report.trace().databaseNanos();
        report.findings().forEach(finding -> badges.add(FindingText.badge(finding)));
    }
}
