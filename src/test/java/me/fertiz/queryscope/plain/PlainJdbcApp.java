package me.fertiz.queryscope.plain;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public final class PlainJdbcApp {

    public static final String EXIT_WITHOUT_CLOSING = "exit-without-closing";

    private static final int BOOKS = 10;

    private PlainJdbcApp() {
    }

    public static void main(String[] arguments) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:plain")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table books (id int primary key, title varchar(100))");
                statement.execute("insert into books select x, 'Book ' || x from system_range(1, " + BOOKS + ")");
            }
            for (int id = 1; id <= BOOKS; id++) {
                System.out.println(title(connection, id));
            }
            if (List.of(arguments).contains(EXIT_WITHOUT_CLOSING)) {
                System.exit(0);
            }
        }
    }

    private static String title(Connection connection, int id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select title from books where id = ?")) {
            select.setInt(1, id);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? result.getString(1) : "";
            }
        }
    }
}
