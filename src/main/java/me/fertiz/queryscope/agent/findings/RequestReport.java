package me.fertiz.queryscope.agent.findings;

import me.fertiz.queryscope.agent.trace.RequestTrace;

import java.util.List;

public record RequestReport(RequestTrace trace, List<Finding> findings) {
}
