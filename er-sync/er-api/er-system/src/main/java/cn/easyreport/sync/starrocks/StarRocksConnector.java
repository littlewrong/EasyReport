package cn.easyreport.sync.starrocks;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.DataSourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * StarRocks connector using plain JDBC.
 * StarRocks is compatible with MySQL protocol, so we use MySQL JDBC driver.
 */
public class StarRocksConnector implements DataSourceConnector {

    private static final Logger log = LoggerFactory.getLogger(StarRocksConnector.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;
    private static final Pattern USE_DATABASE_PATTERN = Pattern.compile(
        "(?i)^\\s*USE\\s+(?:`(?:``|[^`])+`|[\\p{L}\\p{N}_$]+)\\s*;?\\s*$");

    private final ErDatasource datasource;
    private Connection conn;
    private String useDatabaseSql;
    private boolean closed;

    public StarRocksConnector(ErDatasource datasource) throws Exception {
        this.datasource = datasource;
        // StarRocks uses MySQL JDBC driver
        Class.forName(datasource.getDriverClass());
        this.conn = openConnection();
    }

    @Override
    public List<String> listTables(String schemaPattern, String tablePattern) throws Exception {
        return withConnection(connection -> listTables(connection, schemaPattern, tablePattern), true);
    }

    private List<String> listTables(Connection connection, String schemaPattern, String tablePattern) throws SQLException {
        List<String> list = new ArrayList<>();
        DatabaseMetaData meta = connection.getMetaData();

        if (!containsWildcard(tablePattern) && !containsWildcard(schemaPattern)) {
            // Handle explicit table names (comma/semicolon/space separated)
            String[] names = tablePattern.split("[;,\\s]+");
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                try (ResultSet rs = meta.getTables(schemaPattern, null, name, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"));
                    }
                }
            }
        } else {
            // Handle wildcard patterns
            try (ResultSet rs = meta.getTables(schemaPattern, null, tablePattern, new String[]{"TABLE"})) {
                while (rs.next()) {
                    list.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return list;
    }

    @Override
    public boolean tableExists(String schemaPattern, String tableName) throws Exception {
        return withConnection(connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getTables(schemaPattern, null, tableName, new String[]{"TABLE"})) {
                return rs.next();
            }
        }, true);
    }

    @Override
    public String getCreateTableSql(String schemaPattern, String tableName) throws Exception {
        // StarRocks supports SHOW CREATE TABLE like MySQL
        String prefix = (schemaPattern != null && !schemaPattern.isEmpty()) ? ("`" + schemaPattern + "`.") : "";
        String sql = "SHOW CREATE TABLE " + prefix + "`" + tableName + "`";
        return withConnection(connection -> {
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getString("Create Table");
                }
            }
            throw new IllegalStateException("Cannot fetch DDL for table: " + tableName);
        }, true);
    }

    @Override
    public void execute(String sql) throws Exception {
        // DDL may already have succeeded on the server when the response is lost.
        // Recover the connection for the next operation, without replaying DDL.
        withConnection(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
            if (USE_DATABASE_PATTERN.matcher(sql).matches()) {
                useDatabaseSql = sql;
            }
            return null;
        }, false);
    }

    @Override
    public String getDatabaseName() {
        return datasource.getDatabaseName();
    }

    @Override
    public void close() {
        closed = true;
        closeConnection();
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(
            datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword());
        try {
            // USE is session state and must be restored after reconnecting.
            if (useDatabaseSql != null) {
                try (Statement st = connection.createStatement()) {
                    st.execute(useDatabaseSql);
                }
            }
            return connection;
        } catch (SQLException e) {
            try { connection.close(); } catch (SQLException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    private Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("StarRocks connector is closed", "08003");
        }
        if (conn != null) {
            try {
                if (!conn.isClosed() && conn.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                    return conn;
                }
            } catch (SQLException e) {
                log.debug("StarRocks connection validation failed, reconnecting", e);
            }
            log.info("StarRocks connection is no longer valid, reconnecting");
            closeConnection();
        }
        conn = openConnection();
        return conn;
    }

    private <T> T withConnection(SqlOperation<T> operation, boolean retryRead) throws Exception {
        for (int attempt = 0; ; attempt++) {
            Connection connection = getConnection();
            try {
                return operation.execute(connection);
            } catch (SQLException e) {
                if (!isConnectionFailure(e, connection)) {
                    throw e;
                }
                closeConnection();
                if (!retryRead || attempt > 0) {
                    throw e;
                }
                log.warn("StarRocks read lost its connection, retrying once", e);
            }
        }
    }

    private boolean isConnectionFailure(SQLException error, Connection connection) {
        for (SQLException e = error; e != null; e = e.getNextException()) {
            if ((e.getSQLState() != null && e.getSQLState().startsWith("08"))
                || e instanceof SQLNonTransientConnectionException
                || e instanceof SQLTransientConnectionException
                || e instanceof SQLRecoverableException) {
                return true;
            }
        }
        try {
            return connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    private void closeConnection() {
        if (conn != null) {
            try { conn.close(); } catch (Exception e) { log.warn("close conn error", e); }
            conn = null;
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection connection) throws Exception;
    }

    private boolean containsWildcard(String s) {
        if (s == null) return false;
        return s.contains("%") || s.contains("_");
    }
}
