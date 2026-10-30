package me.fertiz.queryscope.agent.findings;

import me.fertiz.queryscope.agent.trace.UnitTrace;

import java.util.List;

public record UnitReport(UnitTrace trace, List<Finding> findings) {
}
