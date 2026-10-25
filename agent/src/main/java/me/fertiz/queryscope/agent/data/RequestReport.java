package me.fertiz.queryscope.agent.data;

import java.util.List;

public record RequestReport(RequestTrace trace, List<Finding> findings) {
}
