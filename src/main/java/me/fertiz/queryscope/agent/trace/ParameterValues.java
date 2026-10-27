package me.fertiz.queryscope.agent.trace;

import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.UUID;

final class ParameterValues {

    private static final int MAX_TEXT = 64;

    private ParameterValues() {
    }

    static String describe(String setter, Object value) {
        if (value == null || setter.equals("setNull")) {
            return "null";
        }
        if (value instanceof CharSequence text) {
            String shown = text.length() > MAX_TEXT ? text.subSequence(0, MAX_TEXT) + "..." : text.toString();
            return "'" + shown + "'";
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof Date || value instanceof TemporalAccessor || value instanceof UUID
                || value instanceof Enum<?>) {
            return value.toString();
        }
        return "<" + value.getClass().getSimpleName() + ">";
    }
}
