package me.fertiz.queryscope.agent.hub;

import java.io.IOException;
import java.util.List;

public interface Processes {

    List<JavaProcess> list();

    int attach(long pid) throws IOException;
}
