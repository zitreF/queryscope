package me.fertiz.queryscope.profiled;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class BookCatalog {

    private final DataSource dataSource;

    public BookCatalog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<String> titles(int... ids) throws SQLException {
        List<String> titles = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (int id : ids) {
                titles.add(title(connection, id));
            }
        }
        return titles;
    }

    public int depth(int levels) {
        return levels == 0 ? 0 : 1 + depth(levels - 1);
    }

    public void reject(String isbn) {
        throw new IllegalArgumentException("Unknown ISBN " + isbn);
    }

    private String title(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select title from books where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
}
