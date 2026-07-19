package firewall;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Probes the master database with a trivial SELECT 1 to detect readiness. */
final class ConnectionProbe implements AutoCloseable {
    private final Connection connection;

    private ConnectionProbe(Connection connection) {
        this.connection = connection;
    }

    static ConnectionProbe probe(String jdbcUrl) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
        return new ConnectionProbe(connection);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
