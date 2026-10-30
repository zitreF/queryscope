package me.fertiz.queryscope.war;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class BookshelfServlet extends HttpServlet {

    private static final String URL = "jdbc:h2:mem:bookshelf;DB_CLOSE_DELAY=-1";
    private static final int BOOKS = 6;

    @Override
    public void init() throws ServletException {
        try {
            Class.forName("org.h2.Driver");
            try (Connection connection = DriverManager.getConnection(URL);
                 Statement statement = connection.createStatement()) {
                statement.execute("create table books (id int primary key, title varchar(100))");
                statement.execute("insert into books select x, 'Book ' || x from system_range(1, " + BOOKS + ")");
            }
        } catch (ClassNotFoundException | SQLException e) {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<String> titles = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL)) {
            for (int id = 1; id <= BOOKS; id++) {
                titles.add(title(connection, id));
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
        response.setContentType("text/plain");
        response.getWriter().println(String.join(", ", titles));
    }

    private static String title(Connection connection, int id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("select title from books where id = ?")) {
            select.setInt(1, id);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getString(1) : "";
            }
        }
    }
}
