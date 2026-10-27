package me.fertiz.queryscope.agent.trace;

import java.util.Map;
import java.util.TreeMap;

final class StatementState {

    private final String sql;
    private final Map<Integer, String> parameters = new TreeMap<>();

    StatementState(String sql) {
        this.sql = sql;
    }

    String sql() {
        return sql;
    }

    synchronized void set(int index, String value) {
        parameters.put(index, value);
    }

    synchronized void clear() {
        parameters.clear();
    }

    synchronized String parameters() {
        return parameters.isEmpty() ? "" : parameters.values().toString();
    }
}
