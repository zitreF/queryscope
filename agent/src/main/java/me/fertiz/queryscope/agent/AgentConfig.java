package me.fertiz.queryscope.agent;

import java.time.Duration;
import java.util.regex.Pattern;

public record AgentConfig(int nPlusOneThreshold, Duration slowQueryThreshold) {

    public static final AgentConfig DEFAULTS = new AgentConfig(5, Duration.ofMillis(100));

    private static final Pattern POSITIVE_NUMBER = Pattern.compile("[1-9][0-9]{0,8}");

    public static AgentConfig parse(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return DEFAULTS;
        }
        int nPlusOneThreshold = DEFAULTS.nPlusOneThreshold();
        Duration slowQueryThreshold = DEFAULTS.slowQueryThreshold();
        for (String option : arguments.split(",")) {
            String[] keyAndValue = option.split("=", 2);
            String key = keyAndValue[0].trim();
            String value = keyAndValue.length == 2 ? keyAndValue[1].trim() : "";
            switch (key) {
                case "nPlusOneThreshold" -> nPlusOneThreshold = positiveNumber(key, value);
                case "slowQueryMs" -> slowQueryThreshold = Duration.ofMillis(positiveNumber(key, value));
                default -> throw new IllegalArgumentException(
                        "Unknown QueryScope option '" + key + "', known options are nPlusOneThreshold and slowQueryMs");
            }
        }
        return new AgentConfig(nPlusOneThreshold, slowQueryThreshold);
    }

    private static int positiveNumber(String key, String value) {
        if (!POSITIVE_NUMBER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "QueryScope option " + key + " needs a positive number, got '" + value + "'");
        }
        return Integer.parseInt(value);
    }
}
