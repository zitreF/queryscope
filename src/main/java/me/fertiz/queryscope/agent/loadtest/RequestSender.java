package me.fertiz.queryscope.agent.loadtest;

import java.io.IOException;
import java.net.URI;

@FunctionalInterface
public interface RequestSender {

    int send(URI target, long run) throws IOException;
}
