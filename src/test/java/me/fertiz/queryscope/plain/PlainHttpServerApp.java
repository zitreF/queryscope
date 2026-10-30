package me.fertiz.queryscope.plain;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class PlainHttpServerApp {

    private static final int STOP_DELAY_SECONDS = 1;

    private PlainHttpServerApp() {
    }

    public static void main(String[] arguments) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:http")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table books (id int primary key, title varchar(100))");
                statement.execute("insert into books values (1, 'Dune'), (2, 'Solaris'), (3, 'Hyperion')");
            }
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/books", exchange -> {
                byte[] body = String.valueOf(countBooks(connection)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/books")).build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("Books: " + response.body());
            server.stop(STOP_DELAY_SECONDS);
        }
    }

    private static int countBooks(Connection connection) throws IOException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from books")) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }
}
