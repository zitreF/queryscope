package me.fertiz.queryscope.agent.http;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalServer {

    private static final int ANY_FREE_PORT = 0;

    private final HttpServer server;
    private final ExecutorService executor;

    private LocalServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static LocalServer start(int port, boolean portGiven, String name, Map<String, HttpHandler> routes)
            throws IOException {
        HttpServer server = bind(port, portGiven);
        ExecutorService executor = Executors.newCachedThreadPool(daemonThreadFactory(name));
        routes.forEach((path, handler) -> server.createContext(path, HttpSupport.localhostOnly(handler)));
        server.setExecutor(executor);
        startOnDaemonThread(server, name);
        return new LocalServer(server, executor);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static HttpServer bind(int port, boolean portGiven) throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (BindException e) {
            if (portGiven || port == ANY_FREE_PORT) {
                throw e;
            }
            return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), ANY_FREE_PORT), 0);
        }
    }

    private static void startOnDaemonThread(HttpServer server, String name) {
        Thread starter = new Thread(server::start, name + "-start");
        starter.setDaemon(true);
        starter.start();
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
