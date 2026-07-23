package cn.easyreport.sync.datasync;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL数据同步提取器
 */
public class PostgreSqlDataSyncExtractor implements DataSyncExtractor {

    @Override
    public List<String> listTables(DatabaseMetaData meta, String dbType, String schemaPattern, String tablePattern) throws SQLException {
        List<String> tables = new ArrayList<>();
        String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : "public";
        
        try (ResultSet rs = meta.getTables(null, schema, tablePattern, new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    @Override
    public List<String> listColumns(Connection conn, String schemaPattern, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : "public";
        
        String sql = "SELECT column_name FROM information_schema.columns " +
                     "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString("column_name"));
                }
            }
        }
        return columns;
    }

    @Override
    public List<String> listPrimaryKeys(Connection conn, String schemaPattern, String table) throws SQLException {
        List<String> pks = new ArrayList<>();
        String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : "public";
        
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getPrimaryKeys(null, schema, table)) {
            while (rs.next()) {
                pks.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pks;
    }

    @Override
    public String buildSelectFull(String schema, String table, String timestampField, List<String> pkColumns) {
        String schemaPrefix = (schema != null && !schema.isEmpty()) ? "\"" + schema + "\"." : "\"public\".";
        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        sql.append(schemaPrefix).append("\"").append(table).append("\"");

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
        String schemaPrefix = (schema != null && !schema.isEmpty()) ? "\"" + schema + "\"." : "\"public\".";
        return "SELECT MIN(\"" + timestampField + "\"), MAX(\"" + timestampField + "\") FROM "
            + schemaPrefix + "\"" + table + "\" WHERE \"" + timestampField + "\" IS NOT NULL";
    }

    @Override
    public String buildSelectFullWindow(String schema, String table, String timestampField, List<String> pkColumns) {
        String schemaPrefix = (schema != null && !schema.isEmpty()) ? "\"" + schema + "\"." : "\"public\".";
        return "SELECT * FROM " + schemaPrefix + "\"" + table + "\" WHERE \""
            + timestampField + "\" >= ? AND \"" + timestampField + "\" < ?";
    }

    @Override
    public String buildSelectIncremental(String schema, String table, String timestampField, List<String> pkColumns) {
        String schemaPrefix = (schema != null && !schema.isEmpty()) ? "\"" + schema + "\"." : "\"public\".";
        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        sql.append(schemaPrefix).append("\"").append(table).append("\"");
        sql.append(" WHERE \"").append(timestampField).append("\" > ?");

        sql.append(" ORDER BY \"").append(timestampField).append("\"");
        if (pkColumns != null && !pkColumns.isEmpty()) {
            sql.append(", \"").append(pkColumns.get(0)).append("\"");
        }

        return sql.toString();
    }
}
