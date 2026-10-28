package me.fertiz.queryscope.agent.launch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record LaunchCommand(List<String> agentOptions, List<String> target, List<String> applicationArguments) {

    public static final String LAUNCHER_PID_PROPERTY = "queryscope.launcherPid";

    private static final Set<String> CLASSPATH_FLAGS = Set.of("-cp", "-classpath", "--class-path");
    private static final List<String> FOREIGN_AGENT_OPTIONS =
            List.of("-agentlib", "-agentpath", "-javaagent", "-Xrunjdwp", "-Xdebug");
    private static final String JFR_OPTIONS = "-XX:FlightRecorderOptions";
    private static final int JFR_STACK_DEPTH = 256;

    public LaunchCommand {
        agentOptions = List.copyOf(agentOptions);
        target = List.copyOf(target);
        applicationArguments = List.copyOf(applicationArguments);
    }

    public static LaunchCommand parse(String... arguments) {
        List<String> agentOptions = new ArrayList<>();
        int index = 0;
        while (index < arguments.length && arguments[index].startsWith("--")
                && !CLASSPATH_FLAGS.contains(arguments[index])) {
            agentOptions.add(agentOption(arguments[index]));
            index++;
        }
        if (index == arguments.length) {
            return new LaunchCommand(agentOptions, List.of(), List.of());
        }
        List<String> target;
        if (CLASSPATH_FLAGS.contains(arguments[index])) {
            if (index + 2 >= arguments.length) {
                throw new IllegalArgumentException(arguments[index] + " needs a classpath and a main class");
            }
            target = List.of("-cp", arguments[index + 1], arguments[index + 2]);
            index += 3;
        } else if (arguments[index].equals("-jar")) {
            if (index + 1 >= arguments.length) {
                throw new IllegalArgumentException("-jar needs the application jar");
            }
            target = List.of("-jar", arguments[index + 1]);
            index += 2;
        } else {
            target = List.of("-jar", arguments[index]);
            index++;
        }
        return new LaunchCommand(agentOptions, target, List.of(arguments).subList(index, arguments.length));
    }

    public static List<String> withDeepJfrStacks(List<String> jvmOptions, String vmName) {
        if (vmName.contains("OpenJ9") || jvmOptions.stream().anyMatch(option -> option.startsWith(JFR_OPTIONS))) {
            return jvmOptions;
        }
        List<String> options = new ArrayList<>(jvmOptions);
        options.add(JFR_OPTIONS + "=stackdepth=" + JFR_STACK_DEPTH);
        return options;
    }

    public String agentOptionString() {
        return String.join(",", agentOptions);
    }

    public List<String> processCommand(String java, Path agentJar, List<String> jvmOptions, long launcherPid) {
        List<String> command = new ArrayList<>();
        command.add(java);
        jvmOptions.stream()
                .filter(option -> FOREIGN_AGENT_OPTIONS.stream().noneMatch(option::startsWith))
                .forEach(command::add);
        command.add("-D" + LAUNCHER_PID_PROPERTY + "=" + launcherPid);
        command.add("-javaagent:" + agentJar + (agentOptions.isEmpty() ? "" : "=" + agentOptionString()));
        command.addAll(target);
        command.addAll(applicationArguments);
        return command;
    }

    private static String agentOption(String argument) {
        String option = argument.substring(2);
        return option.contains("=") ? option : option + "=true";
    }
}
