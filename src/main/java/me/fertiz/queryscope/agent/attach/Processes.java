package me.fertiz.queryscope.agent.attach;

import java.io.IOException;
import java.util.List;

public interface Processes {

    List<JavaProcess> list();

    int attach(long pid) throws IOException;
}
