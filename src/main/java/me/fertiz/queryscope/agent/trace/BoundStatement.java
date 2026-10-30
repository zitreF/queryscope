package me.fertiz.queryscope.agent.trace;

import java.util.Map;
import java.util.TreeMap;

final class BoundStatement {

    private final String sql;
    private final Map<Integer, String> parameters = new TreeMap<>();

    BoundStatement(String sql) {
        this.sql = sql;
    }

    String sql() {
        return sql;
    }

    synchronized void bind(int index, String value) {
        parameters.put(index, value);
    }

    synchronized void clear() {
        parameters.clear();
    }

    synchronized String parametersText() {
        return parameters.isEmpty() ? "" : parameters.values().toString();
    }
}
