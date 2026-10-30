package me.fertiz.queryscope.agent.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonReader {

    private static final int MAX_DEPTH = 64;

    private final String text;
    private int position;
    private int depth;

    private JsonReader(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        reader.skipWhitespace();
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.position != text.length()) {
            throw reader.error("unexpected text after the value");
        }
        return value;
    }

    private Object readValue() {
        if (position >= text.length()) {
            throw error("the text ends before a value");
        }
        char next = text.charAt(position);
        return switch (next) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        enterNesting();
        position++;
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) {
            depth--;
            return object;
        }
        do {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != '"') {
                throw error("expected a quoted name");
            }
            String name = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(name, readValue());
            skipWhitespace();
        } while (consume(','));
        expect('}');
        depth--;
        return object;
    }

    private List<Object> readArray() {
        enterNesting();
        position++;
        List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) {
            depth--;
            return array;
        }
        do {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
        } while (consume(','));
        expect(']');
        depth--;
        return array;
    }

    private String readString() {
        position++;
        StringBuilder value = new StringBuilder();
        while (position < text.length()) {
            char next = text.charAt(position++);
            if (next == '"') {
                return value.toString();
            }
            if (next == '\\') {
                value.append(readEscape());
            } else if (next < ' ') {
                throw error("control character inside a string");
            } else {
                value.append(next);
            }
        }
        throw error("the string is not closed");
    }

    private char readEscape() {
        if (position >= text.length()) {
            throw error("the text ends inside an escape");
        }
        char escaped = text.charAt(position++);
        return switch (escaped) {
            case '"', '\\', '/' -> escaped;
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw error("unknown escape \\" + escaped);
        };
    }

    private char readUnicodeEscape() {
        if (position + 4 > text.length()) {
            throw error("the text ends inside a \\u escape");
        }
        try {
            char value = (char) Integer.parseInt(text.substring(position, position + 4), 16);
            position += 4;
            return value;
        } catch (NumberFormatException e) {
            throw error("invalid \\u escape");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, position)) {
            throw error("unexpected character");
        }
        position += literal.length();
        return value;
    }

    private Object readNumber() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String number = text.substring(start, position);
        if (number.isEmpty()) {
            throw error("unexpected character");
        }
        try {
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            throw error("invalid number " + number);
        }
    }

    private void enterNesting() {
        if (++depth > MAX_DEPTH) {
            throw error("nested more than " + MAX_DEPTH + " levels deep");
        }
    }

    private void skipWhitespace() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }

    private boolean consume(char expected) {
        if (position < text.length() && text.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) {
            throw error("expected '" + expected + "'");
        }
    }

    private IllegalArgumentException error(String problem) {
        return new IllegalArgumentException("Invalid JSON at character " + position + ": " + problem);
    }
}
