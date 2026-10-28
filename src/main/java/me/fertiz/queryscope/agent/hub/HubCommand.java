package me.fertiz.queryscope.agent.hub;

import me.fertiz.queryscope.agent.AgentConfig;
import me.fertiz.queryscope.agent.http.Browser;
import me.fertiz.queryscope.agent.http.LocalServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

public record HubCommand(int port, boolean portGiven, boolean openBrowser, long attachPid, List<String> agentOptions) {

    public static final int DEFAULT_PORT = 7070;
    public static final long NO_PID = 0;

    private static final Pattern PROCESS_ID = Pattern.compile("[1-9][0-9]{0,17}");

    public HubCommand {
        agentOptions = List.copyOf(agentOptions);
    }

    public static HubCommand parse(List<String> options) {
        int port = DEFAULT_PORT;
        boolean portGiven = false;
        boolean openBrowser = true;
        long attachPid = NO_PID;
        List<String> agentOptions = new ArrayList<>();
        for (String option : options) {
            String[] keyAndValue = option.split("=", 2);
            String value = keyAndValue.length == 2 ? keyAndValue[1] : "";
            switch (keyAndValue[0]) {
                case "port" -> {
                    port = AgentConfig.portNumber(value);
                    portGiven = true;
                }
                case "open" -> openBrowser = AgentConfig.flag("open", value);
                case "attach" -> attachPid = processId(value);
                default -> agentOptions.add(option);
            }
        }
        AgentConfig.parse(String.join(",", agentOptions));
        return new HubCommand(port, portGiven, openBrowser, attachPid, agentOptions);
    }

    public void run(Path agentJar) throws IOException, InterruptedException {
        LocalProcesses processes = new LocalProcesses(agentJar, agentOptions);
        if (attachPid != NO_PID) {
            int panelPort = processes.attach(attachPid);
            System.out.println("QueryScope is attached to process " + attachPid
                    + ", its panel is at http://localhost:" + panelPort);
            return;
        }
        LocalServer hub = HubServer.start(port, portGiven, processes);
        String url = "http://localhost:" + hub.port();
        System.out.println("QueryScope hub is running at " + url + ", pick a Java process there. Ctrl+C stops the hub.");
        if (openBrowser) {
            Browser.open(url);
        }
        new CountDownLatch(1).await();
    }

    private static long processId(String value) {
        if (!PROCESS_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("QueryScope option attach needs a process id, got '" + value + "'");
        }
        return Long.parseLong(value);
    }
}
