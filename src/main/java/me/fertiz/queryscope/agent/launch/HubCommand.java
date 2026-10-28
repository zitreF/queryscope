package me.fertiz.queryscope.agent.launch;

import me.fertiz.queryscope.agent.AgentConfig;
import me.fertiz.queryscope.agent.attach.LocalProcesses;
import me.fertiz.queryscope.agent.panel.Browser;
import me.fertiz.queryscope.agent.panel.HubServer;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public record HubCommand(int port, boolean openBrowser, long attachPid, List<String> agentOptions) {

    public static final int DEFAULT_PORT = 7070;
    public static final long NO_PID = 0;

    public HubCommand {
        agentOptions = List.copyOf(agentOptions);
    }

    public static HubCommand parse(List<String> options) {
        int port = DEFAULT_PORT;
        boolean openBrowser = true;
        long attachPid = NO_PID;
        List<String> agentOptions = new ArrayList<>();
        for (String option : options) {
            String[] keyAndValue = option.split("=", 2);
            String value = keyAndValue.length == 2 ? keyAndValue[1] : "";
            switch (keyAndValue[0]) {
                case "port" -> port = number("port", value, 65535);
                case "open" -> openBrowser = !value.equals("false");
                case "attach" -> attachPid = number("attach", value, Long.MAX_VALUE);
                default -> agentOptions.add(option);
            }
        }
        AgentConfig.parse(String.join(",", agentOptions));
        return new HubCommand(port, openBrowser, attachPid, agentOptions);
    }

    public void run(Path agentJar) throws IOException, InterruptedException {
        LocalProcesses processes = new LocalProcesses(agentJar, agentOptions);
        if (attachPid != NO_PID) {
            int panelPort = processes.attach(attachPid);
            System.out.println("QueryScope is attached to process " + attachPid
                    + ", its panel is at http://localhost:" + panelPort);
            return;
        }
        HubServer hub = startHub(processes);
        String url = "http://localhost:" + hub.port();
        System.out.println("QueryScope hub is running at " + url + ", pick a Java process there. Ctrl+C stops the hub.");
        if (openBrowser) {
            Browser.open(url);
        }
        new CountDownLatch(1).await();
    }

    private HubServer startHub(LocalProcesses processes) throws IOException {
        try {
            return HubServer.start(port, processes);
        } catch (BindException e) {
            if (port != DEFAULT_PORT) {
                throw e;
            }
            return HubServer.start(0, processes);
        }
    }

    private static long number(String name, String value, long max) {
        try {
            long number = Long.parseLong(value);
            if (number <= 0 || number > max) {
                throw new IllegalArgumentException("QueryScope option " + name + " is out of range: " + value);
            }
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("QueryScope option " + name + " needs a number, got '" + value + "'");
        }
    }

    private static int number(String name, String value, int max) {
        return (int) number(name, value, (long) max);
    }
}
