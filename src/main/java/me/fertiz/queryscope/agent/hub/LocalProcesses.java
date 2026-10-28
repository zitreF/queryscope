package me.fertiz.queryscope.agent.hub;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import me.fertiz.queryscope.agent.QueryScopeAgent;
import me.fertiz.queryscope.agent.launch.QueryScopeLauncher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class LocalProcesses implements Processes {

    private static final String UNNAMED = "Java process";
    private static final Pattern MAIN_CLASS = Pattern.compile("[\\w$.]+");
    private static final Pattern JAR = Pattern.compile("(.+?\\.jar)(?=\\s|$)", Pattern.CASE_INSENSITIVE);

    private final Path agentJar;
    private final List<String> agentOptions;
    private final Map<Long, Integer> attached = new ConcurrentHashMap<>();

    public LocalProcesses(Path agentJar, List<String> agentOptions) {
        this.agentJar = agentJar;
        this.agentOptions = List.copyOf(agentOptions);
    }

    @Override
    public List<JavaProcess> list() {
        String self = String.valueOf(ProcessHandle.current().pid());
        List<VirtualMachineDescriptor> machines = VirtualMachine.list().stream()
                .filter(machine -> !machine.id().equals(self) && !isQueryScope(machine.displayName()))
                .toList();
        attached.keySet().retainAll(machines.stream().map(machine -> Long.parseLong(machine.id()))
                .collect(Collectors.toSet()));
        return machines.stream()
                .map(this::process)
                .sorted(Comparator.comparing(JavaProcess::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public synchronized int attach(long pid) throws IOException {
        Integer known = attached.get(pid);
        if (known != null) {
            return known;
        }
        int port = loadAgent(pid);
        attached.put(pid, port);
        return port;
    }

    static String name(String command) {
        String program = program(command);
        if (program.isEmpty()) {
            return UNNAMED;
        }
        if (program.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1);
        }
        return program.substring(program.lastIndexOf('.') + 1);
    }

    static boolean isQueryScope(String command) {
        String program = program(command);
        return program.endsWith("queryscope.jar") || program.equals(QueryScopeLauncher.class.getName());
    }

    private static String program(String command) {
        String trimmed = command.trim();
        String first = trimmed.split("\\s+", 2)[0];
        if (MAIN_CLASS.matcher(first).matches() && !first.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return first;
        }
        Matcher jar = JAR.matcher(trimmed);
        return jar.lookingAt() ? jar.group(1) : first;
    }

    private JavaProcess process(VirtualMachineDescriptor machine) {
        long pid = Long.parseLong(machine.id());
        return new JavaProcess(pid, name(machine.displayName()), machine.displayName(),
                attached.getOrDefault(pid, JavaProcess.NOT_ATTACHED));
    }

    private int loadAgent(long pid) throws IOException {
        VirtualMachine machine;
        try {
            machine = VirtualMachine.attach(String.valueOf(pid));
        } catch (AttachNotSupportedException e) {
            throw new IOException("QueryScope cannot attach to process " + pid + ": " + e.getMessage(), e);
        }
        try {
            String port = panelPort(machine);
            if (port == null) {
                machine.loadAgent(agentJar.toString(), agentOptions(freePort()));
                port = panelPort(machine);
            }
            if (port == null) {
                throw new IOException("QueryScope started in process " + pid
                        + " but its panel did not, the application's log says why");
            }
            return Integer.parseInt(port);
        } catch (AgentLoadException e) {
            throw new IOException("Process " + pid + " could not load QueryScope, it needs Java 17 or newer: "
                    + e.getMessage(), e);
        } catch (AgentInitializationException e) {
            throw new IOException("QueryScope failed to start in process " + pid + ": " + e.getMessage(), e);
        } finally {
            machine.detach();
        }
    }

    private static String panelPort(VirtualMachine machine) throws IOException {
        return machine.getSystemProperties().getProperty(QueryScopeAgent.PANEL_PORT_PROPERTY);
    }

    private String agentOptions(int port) {
        List<String> options = new ArrayList<>(agentOptions);
        options.add("port=" + port);
        return String.join(",", options);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
