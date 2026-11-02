package me.fertiz.queryscope.agent.loadtest;

import java.io.IOException;

@FunctionalInterface
public interface RequestSender {

    int send(LoadTestPlan plan, long run) throws IOException;
}
