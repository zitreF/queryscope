package me.fertiz.queryscope.agent.panel;

import java.util.Locale;

final class JsonWriter {

    private final StringBuilder json = new StringBuilder();
    private boolean needsComma;

    JsonWriter beginObject() {
        separate();
        json.append('{');
        needsComma = false;
        return this;
    }

    JsonWriter endObject() {
        json.append('}');
        needsComma = true;
        return this;
    }

    JsonWriter beginArray() {
        separate();
        json.append('[');
        needsComma = false;
        return this;
    }

    JsonWriter endArray() {
        json.append(']');
        needsComma = true;
        return this;
    }

    JsonWriter name(String name) {
        separate();
        quote(name);
        json.append(':');
        needsComma = false;
        return this;
    }

    JsonWriter value(String value) {
        separate();
        if (value == null) {
            json.append("null");
        } else {
            quote(value);
        }
        needsComma = true;
        return this;
    }

    JsonWriter nullValue() {
        separate();
        json.append("null");
        needsComma = true;
        return this;
    }

    JsonWriter value(long value) {
        separate();
        json.append(value);
        needsComma = true;
        return this;
    }

    JsonWriter value(double value) {
        separate();
        json.append(String.format(Locale.ROOT, "%.3f", value));
        needsComma = true;
        return this;
    }

    JsonWriter value(boolean value) {
        separate();
        json.append(value);
        needsComma = true;
        return this;
    }

    @Override
    public String toString() {
        return json.toString();
    }

    private void separate() {
        if (needsComma) {
            json.append(',');
        }
    }

    private void quote(String text) {
        json.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20 || character == ' ' || character == ' ') {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
