package me.fertiz.queryscope.agent.hub;

public record JavaProcess(long pid, String name, String command, int panelPort) {

    public static final int NOT_ATTACHED = 0;

    public boolean attached() {
        return panelPort != NOT_ATTACHED;
    }
}
