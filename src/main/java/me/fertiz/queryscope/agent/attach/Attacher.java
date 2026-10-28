package me.fertiz.queryscope.agent.attach;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import me.fertiz.queryscope.agent.QueryScopeAgent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class Attacher {

    private static final String UNNAMED = "Java process";
    private static final String LAUNCHER = "me.fertiz.queryscope.agent.launch.QueryScopeLauncher";

    private Attacher() {
    }

    public static List<JavaProcess> list() {
        String self = String.valueOf(ProcessHandle.current().pid());
        return VirtualMachine.list().stream()
                .filter(machine -> !machine.id().equals(self) && !isQueryScope(machine.displayName()))
                .map(Attacher::process)
                .sorted(Comparator.comparing(JavaProcess::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public static int attach(long pid, Path agentJar, String options) throws IOException {
        VirtualMachine machine;
        try {
            machine = VirtualMachine.attach(String.valueOf(pid));
        } catch (AttachNotSupportedException e) {
            throw new IOException("QueryScope cannot attach to process " + pid + ": " + e.getMessage(), e);
        }
        try {
            String port = machine.getSystemProperties().getProperty(QueryScopeAgent.PANEL_PORT_PROPERTY);
            if (port == null) {
                machine.loadAgent(agentJar.toString(), options);
                port = machine.getSystemProperties().getProperty(QueryScopeAgent.PANEL_PORT_PROPERTY);
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

    static String name(String command) {
        String first = command.isBlank() ? "" : command.trim().split("\\s+", 2)[0];
        if (first.isEmpty()) {
            return UNNAMED;
        }
        if (first.endsWith(".jar")) {
            return Path.of(first).getFileName().toString();
        }
        return first.substring(first.lastIndexOf('.') + 1);
    }

    static boolean isQueryScope(String command) {
        String first = command.isBlank() ? "" : command.trim().split("\\s+", 2)[0];
        return first.endsWith("queryscope.jar") || first.equals(LAUNCHER);
    }

    private static JavaProcess process(VirtualMachineDescriptor machine) {
        return new JavaProcess(Long.parseLong(machine.id()), name(machine.displayName()), machine.displayName(),
                JavaProcess.NOT_ATTACHED);
    }
}
