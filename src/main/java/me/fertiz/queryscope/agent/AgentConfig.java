package me.fertiz.queryscope.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public record AgentConfig(int port, int nPlusOneThreshold, Duration slowQueryThreshold, List<String> entryMethods) {

    public static final AgentConfig DEFAULTS = new AgentConfig(7070, 5, Duration.ofMillis(100), List.of());

    private static final Pattern POSITIVE_NUMBER = Pattern.compile("[1-9][0-9]{0,8}");
    private static final Pattern ENTRY_METHOD = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$.]*#[\\p{L}_$][\\p{L}\\p{N}_$]*");

    public AgentConfig {
        entryMethods = List.copyOf(entryMethods);
    }

    public static AgentConfig parse(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return DEFAULTS;
        }
        int port = DEFAULTS.port();
        int nPlusOneThreshold = DEFAULTS.nPlusOneThreshold();
        Duration slowQueryThreshold = DEFAULTS.slowQueryThreshold();
        List<String> entryMethods = new ArrayList<>();
        for (String option : arguments.split(",")) {
            String[] keyAndValue = option.split("=", 2);
            String key = keyAndValue[0].trim();
            String value = keyAndValue.length == 2 ? keyAndValue[1].trim() : "";
            switch (key) {
                case "port" -> port = portNumber(value);
                case "nPlusOneThreshold" -> nPlusOneThreshold = positiveNumber(key, value);
                case "slowQueryMs" -> slowQueryThreshold = Duration.ofMillis(positiveNumber(key, value));
                case "entry" -> entryMethods.add(entryMethod(value));
                default -> throw new IllegalArgumentException("Unknown QueryScope option '" + key
                        + "', known options are port, nPlusOneThreshold, slowQueryMs and entry");
            }
        }
        return new AgentConfig(port, nPlusOneThreshold, slowQueryThreshold, entryMethods);
    }

    private static String entryMethod(String value) {
        if (!ENTRY_METHOD.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "QueryScope option entry needs a class and a method like com.acme.ImportJob#run, got '" + value + "'");
        }
        return value;
    }

    private static int portNumber(String value) {
        int port = positiveNumber("port", value);
        if (port > 65535) {
            throw new IllegalArgumentException("QueryScope option port must be at most 65535, got " + port);
        }
        return port;
    }

    private static int positiveNumber(String key, String value) {
        if (!POSITIVE_NUMBER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "QueryScope option " + key + " needs a positive number, got '" + value + "'");
        }
        return Integer.parseInt(value);
    }
}
