package me.fertiz.queryscope.agent.findings;

import java.util.regex.Pattern;

public final class SqlNormalizer {

    private static final Pattern IN_LIST = Pattern.compile("\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");

    private SqlNormalizer() {
    }

    public static String normalize(String sql) {
        StringBuilder normalized = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = indexAfterQuoted(sql, index);
                normalized.append('?');
            } else if (current == '"' || current == '`') {
                int end = indexAfterQuoted(sql, index);
                normalized.append(sql, index, end);
                index = end;
            } else if (sql.startsWith("--", index)) {
                index = indexAfterLineComment(sql, index);
                appendSpace(normalized);
            } else if (sql.startsWith("/*", index)) {
                index = indexAfterBlockComment(sql, index);
                appendSpace(normalized);
            } else if (Character.isWhitespace(current)) {
                index++;
                appendSpace(normalized);
            } else if (Character.isDigit(current) && !endsWithIdentifier(normalized)) {
                index = indexAfterNumber(sql, index);
                normalized.append('?');
            } else {
                normalized.append(Character.toLowerCase(current));
                index++;
            }
        }
        String collapsed = IN_LIST.matcher(normalized.toString().trim()).replaceAll("in (?)");
        return collapsed.endsWith(";") ? collapsed.substring(0, collapsed.length() - 1).trim() : collapsed;
    }

    private static int indexAfterQuoted(String sql, int start) {
        char quote = sql.charAt(start);
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) != quote) {
                index++;
            } else if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                index += 2;
            } else {
                return index + 1;
            }
        }
        return index;
    }

    private static int indexAfterLineComment(String sql, int start) {
        int end = sql.indexOf('\n', start);
        return end < 0 ? sql.length() : end + 1;
    }

    private static int indexAfterBlockComment(String sql, int start) {
        int end = sql.indexOf("*/", start + 2);
        return end < 0 ? sql.length() : end + 2;
    }

    private static int indexAfterNumber(String sql, int start) {
        int index = start;
        while (index < sql.length() && (Character.isDigit(sql.charAt(index)) || sql.charAt(index) == '.')) {
            index++;
        }
        return index;
    }

    private static boolean endsWithIdentifier(StringBuilder normalized) {
        if (normalized.isEmpty()) {
            return false;
        }
        char last = normalized.charAt(normalized.length() - 1);
        return Character.isLetterOrDigit(last) || last == '_' || last == '$';
    }

    private static void appendSpace(StringBuilder normalized) {
        if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) != ' ') {
            normalized.append(' ');
        }
    }
}
