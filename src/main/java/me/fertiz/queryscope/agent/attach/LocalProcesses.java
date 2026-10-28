package me.fertiz.queryscope.agent.attach;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class LocalProcesses implements Processes {

    private final Path agentJar;
    private final List<String> agentOptions;
    private final Map<Long, Integer> attached = new ConcurrentHashMap<>();

    public LocalProcesses(Path agentJar, List<String> agentOptions) {
        this.agentJar = agentJar;
        this.agentOptions = List.copyOf(agentOptions);
    }

    @Override
    public List<JavaProcess> list() {
        List<JavaProcess> processes = Attacher.list();
        Set<Long> running = processes.stream().map(JavaProcess::pid).collect(Collectors.toSet());
        attached.keySet().retainAll(running);
        return processes.stream()
                .map(process -> attached.containsKey(process.pid())
                        ? process.withPanelPort(attached.get(process.pid()))
                        : process)
                .toList();
    }

    @Override
    public synchronized int attach(long pid) throws IOException {
        Integer known = attached.get(pid);
        if (known != null) {
            return known;
        }
        int port = Attacher.attach(pid, agentJar, options(freePort()));
        attached.put(pid, port);
        return port;
    }

    private String options(int port) {
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
