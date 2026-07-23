package cn.easyreport.sync.datasync;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle数据同步提取器
 */
public class OracleDataSyncExtractor implements DataSyncExtractor {

    @Override
    public List<String> listTables(DatabaseMetaData meta, String dbType, String schemaPattern, String tablePattern) throws SQLException {
        List<String> tables = new ArrayList<>();
        String schema = resolveSchema(meta, schemaPattern);

        try (ResultSet rs = meta.getTables(null, schema, tablePattern != null ? tablePattern.toUpperCase() : "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    @Override
    public List<String> listColumns(Connection conn, String schemaPattern, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        String schema = resolveSchema(conn.getMetaData(), schemaPattern);

        String sql = "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS " +
                     "WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    @Override
    public List<String> listPrimaryKeys(Connection conn, String schemaPattern, String table) throws SQLException {
        List<String> pks = new ArrayList<>();
        String schema = resolveSchema(conn.getMetaData(), schemaPattern);

        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getPrimaryKeys(null, schema, table.toUpperCase())) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    @Override
    public String buildSelectFull(String schema, String table, String timestampField, List<String> pkColumns) {
        String tableName = buildTableName(schema, table);
        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        sql.append(tableName);

        sql.append(" ORDER BY ");
        if (timestampField != null && !timestampField.isEmpty()) {
            sql.append("\"").append(timestampField).append("\"");
            if (pkColumns != null && !pkColumns.isEmpty()) {
                sql.append(", \"").append(pkColumns.get(0)).append("\"");
            }
        } else if (pkColumns != null && !pkColumns.isEmpty()) {
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("\"").append(pkColumns.get(i)).append("\"");
            }
        }

        return sql.toString();
    }

    @Override
    public boolean supportsTimestampWindowFullSync() {
        return true;
    }

    @Override
    public String buildSelectTimestampBounds(String schema, String table, String timestampField) {
        String tableName = buildTableName(schema, table);
        return "SELECT MIN(\"" + timestampField + "\"), MAX(\"" + timestampField + "\") FROM "
            + tableName + " WHERE \"" + timestampField + "\" IS NOT NULL";
    }

    @Override
    public String buildSelectFullWindow(String schema, String table, String timestampField, List<String> pkColumns) {
        String tableName = buildTableName(schema, table);
        return "SELECT * FROM " + tableName + " WHERE \""
            + timestampField + "\" >= ? AND \"" + timestampField + "\" < ?";
    }

    @Override
    public String buildSelectIncremental(String schema, String table, String timestampField, List<String> pkColumns) {
        String tableName = buildTableName(schema, table);
        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        sql.append(tableName);
        sql.append(" WHERE \"").append(timestampField).append("\" > ?");

        sql.append(" ORDER BY \"").append(timestampField).append("\"");
        if (pkColumns != null && !pkColumns.isEmpty()) {
            sql.append(", \"").append(pkColumns.get(0)).append("\"");
        }

        return sql.toString();
    }

    private String resolveSchema(DatabaseMetaData meta, String schemaPattern) throws SQLException {
        if (schemaPattern != null && !schemaPattern.isEmpty()) {
            return schemaPattern.toUpperCase();
        }
        // 默认使用连接用户名作为 Schema
        String userName = meta.getUserName();
        return userName != null ? userName.toUpperCase() : null;
    }

    private String buildTableName(String schema, String table) {
        String schemaPrefix = (schema != null && !schema.isEmpty())
            ? "\"" + schema.toUpperCase() + "\"."
            : "";
        return schemaPrefix + "\"" + table.toUpperCase() + "\"";
    }
}
