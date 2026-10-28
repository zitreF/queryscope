package me.fertiz.queryscope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

final class ImportJob {

    private final DataSource dataSource;

    ImportJob(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void run() throws SQLException {
        for (int id = 1; id <= 2; id++) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("select title from books where id = ?")) {
                statement.setInt(1, id);
                statement.executeQuery().close();
            }
        }
    }
}
