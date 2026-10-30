package me.fertiz.queryscope.agent.http;

import java.util.Locale;

public final class JsonWriter {

    private final StringBuilder json = new StringBuilder();
    private boolean needsComma;

    public JsonWriter beginObject() {
        writeCommaIfNeeded();
        json.append('{');
        needsComma = false;
        return this;
    }

    public JsonWriter endObject() {
        json.append('}');
        needsComma = true;
        return this;
    }

    public JsonWriter beginArray() {
        writeCommaIfNeeded();
        json.append('[');
        needsComma = false;
        return this;
    }

    public JsonWriter endArray() {
        json.append(']');
        needsComma = true;
        return this;
    }

    public JsonWriter name(String name) {
        writeCommaIfNeeded();
        writeQuoted(name);
        json.append(':');
        needsComma = false;
        return this;
    }

    public JsonWriter value(String value) {
        writeCommaIfNeeded();
        if (value == null) {
            json.append("null");
        } else {
            writeQuoted(value);
        }
        needsComma = true;
        return this;
    }

    public JsonWriter nullValue() {
        writeCommaIfNeeded();
        json.append("null");
        needsComma = true;
        return this;
    }

    public JsonWriter value(long value) {
        writeCommaIfNeeded();
        json.append(value);
        needsComma = true;
        return this;
    }

    public JsonWriter value(double value) {
        writeCommaIfNeeded();
        json.append(String.format(Locale.ROOT, "%.3f", value));
        needsComma = true;
        return this;
    }

    public JsonWriter value(boolean value) {
        writeCommaIfNeeded();
        json.append(value);
        needsComma = true;
        return this;
    }

    @Override
    public String toString() {
        return json.toString();
    }

    private void writeCommaIfNeeded() {
        if (needsComma) {
            json.append(',');
        }
    }

    private void writeQuoted(String text) {
        json.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case ' ', ' ' -> json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                default -> {
                    if (character < 0x20) {
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
