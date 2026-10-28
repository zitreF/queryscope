package me.fertiz.queryscope.agent;

import me.fertiz.queryscope.agent.findings.Thresholds;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public record AgentConfig(
        int port,
        boolean portGiven,
        int nPlusOneThreshold,
        Duration slowQueryThreshold,
        int largeResultRows,
        int maxQueries,
        Duration slowMethodThreshold,
        List<String> entryMethods,
        List<String> packages,
        boolean callTree,
        boolean sampling,
        Duration sampleInterval,
        boolean openPanel,
        boolean printSummary
) {

    public static final AgentConfig DEFAULTS = new AgentConfig(7070, false, 5, Duration.ofMillis(100), 500, 30,
            Duration.ofMillis(100), List.of(), List.of(), true, true, Duration.ofMillis(10), false, true);

    private static final int MAX_SAMPLE_MILLIS = 1000;

    private static final Pattern POSITIVE_NUMBER = Pattern.compile("[1-9][0-9]{0,8}");
    private static final String IDENTIFIER = "[\\p{L}_$][\\p{L}\\p{N}_$]*";
    private static final Pattern PACKAGE = Pattern.compile(IDENTIFIER + "(\\." + IDENTIFIER + ")*");
    private static final Pattern ENTRY_METHOD = Pattern.compile(PACKAGE.pattern() + "#" + IDENTIFIER);

    public AgentConfig {
        entryMethods = List.copyOf(entryMethods);
        packages = List.copyOf(packages);
    }

    public static AgentConfig parse(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return DEFAULTS;
        }
        int port = DEFAULTS.port();
        boolean portGiven = DEFAULTS.portGiven();
        int nPlusOneThreshold = DEFAULTS.nPlusOneThreshold();
        Duration slowQueryThreshold = DEFAULTS.slowQueryThreshold();
        int largeResultRows = DEFAULTS.largeResultRows();
        int maxQueries = DEFAULTS.maxQueries();
        Duration slowMethodThreshold = DEFAULTS.slowMethodThreshold();
        List<String> entryMethods = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        boolean callTree = DEFAULTS.callTree();
        boolean sampling = DEFAULTS.sampling();
        Duration sampleInterval = DEFAULTS.sampleInterval();
        boolean openPanel = DEFAULTS.openPanel();
        boolean printSummary = DEFAULTS.printSummary();
        for (String option : arguments.split(",")) {
            if (option.isBlank()) {
                continue;
            }
            String[] keyAndValue = option.split("=", 2);
            String key = keyAndValue[0].trim();
            String value = keyAndValue.length == 2 ? keyAndValue[1].trim() : "";
            switch (key) {
                case "port" -> {
                    port = portNumber(value);
                    portGiven = true;
                }
                case "nPlusOneThreshold" -> nPlusOneThreshold = positiveNumber(key, value);
                case "slowQueryMs" -> slowQueryThreshold = Duration.ofMillis(positiveNumber(key, value));
                case "largeResultRows" -> largeResultRows = positiveNumber(key, value);
                case "maxQueries" -> maxQueries = positiveNumber(key, value);
                case "slowMethodMs" -> slowMethodThreshold = Duration.ofMillis(positiveNumber(key, value));
                case "entry" -> entryMethods.add(entryMethod(value));
                case "package" -> packages.add(packageName(value));
                case "profile" -> callTree = flag(key, value);
                case "sampling" -> sampling = flag(key, value);
                case "sampleMs" -> sampleInterval = Duration.ofMillis(sampleMillis(value));
                case "open" -> openPanel = flag(key, value);
                case "summary" -> printSummary = flag(key, value);
                default -> throw new IllegalArgumentException("Unknown QueryScope option '" + key
                        + "', known options are port, nPlusOneThreshold, slowQueryMs, largeResultRows, maxQueries,"
                        + " slowMethodMs, entry, package, profile, sampling, sampleMs, open and summary");
            }
        }
        return new AgentConfig(port, portGiven, nPlusOneThreshold, slowQueryThreshold, largeResultRows, maxQueries,
                slowMethodThreshold, entryMethods, packages, callTree, sampling, sampleInterval, openPanel, printSummary);
    }

    public Thresholds thresholds() {
        return new Thresholds(nPlusOneThreshold, slowQueryThreshold, largeResultRows, maxQueries, slowMethodThreshold);
    }

    private static String entryMethod(String value) {
        if (!ENTRY_METHOD.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "QueryScope option entry needs a class and a method like com.acme.ImportJob#run, got '" + value + "'");
        }
        return value;
    }

    private static int sampleMillis(String value) {
        int millis = positiveNumber("sampleMs", value);
        if (millis > MAX_SAMPLE_MILLIS) {
            throw new IllegalArgumentException(
                    "QueryScope option sampleMs must be at most " + MAX_SAMPLE_MILLIS + ", got " + millis);
        }
        return millis;
    }

    private static String packageName(String value) {
        if (!PACKAGE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "QueryScope option package needs a Java package like com.acme.shop, got '" + value + "'");
        }
        return value;
    }

    public static boolean flag(String key, String value) {
        return switch (value) {
            case "", "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    "QueryScope option " + key + " needs true or false, got '" + value + "'");
        };
    }

    public static int portNumber(String value) {
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
