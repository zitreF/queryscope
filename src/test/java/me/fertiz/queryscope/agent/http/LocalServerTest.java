package me.fertiz.queryscope.agent.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalServerTest {

    @Test
    void takesAFreePortWhenTheDefaultOneIsTaken() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            LocalServer server = LocalServer.start(taken.getLocalPort(), false, "test", Map.of());
            try {
                assertNotEquals(taken.getLocalPort(), server.port());
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void failsWhenAGivenPortIsTaken() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            assertThrows(BindException.class, () -> LocalServer.start(taken.getLocalPort(), true, "test", Map.of()));
        }
    }
}
