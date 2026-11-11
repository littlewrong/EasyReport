package cn.easyreport.sync.kingbase;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Shared JDBC operations which do not depend on the Kingbase compatibility mode. */
public final class KingbaseJdbcSupport {
    private KingbaseJdbcSupport() {
    }

    public static String currentSchema(Connection connection) throws SQLException {
        try {
            String schema = connection.getSchema();
            if (schema != null && !schema.isEmpty()) return schema;
        } catch (SQLException | AbstractMethodError ignored) {
            // Older JDBC drivers may not implement Connection.getSchema().
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT current_schema()")) {
            if (rows.next()) {
                String schema = rows.getString(1);
                if (schema != null && !schema.isEmpty()) return schema;
            }
        }
        throw new SQLException("金仓连接没有可用的当前 Schema，请配置有效的 search_path 或显式指定 Schema");
    }

    public static String quoteIdentifier(String identifier) {
        if (identifier == null) throw new IllegalArgumentException("identifier is null");
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Escape exact names passed to JDBC methods accepting SQL LIKE patterns. */
    public static String escapePattern(DatabaseMetaData metadata, String name) throws SQLException {
        if (name == null) return null;
        String escape = metadata.getSearchStringEscape();
        if (escape == null || escape.isEmpty()) escape = "\\";
        return name.replace(escape, escape + escape).replace("%", escape + "%").replace("_", escape + "_");
    }
}
