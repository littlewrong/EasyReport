package cn.easyreport.sync.datasync;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server数据同步提取器
 */
public class SqlServerDataSyncExtractor implements DataSyncExtractor {

    @Override
    public List<String> listTables(DatabaseMetaData meta, String dbType, String schemaPattern, String tablePattern) throws SQLException {
        List<String> list = new ArrayList<>();
        // SQL Server: schemaPattern 实际上是数据库名(catalog)，不是 schema
        // 与预览逻辑保持一致：将 schemaPattern 作为 catalog 参数传递
        String catalog = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : null;
        try (ResultSet rs = meta.getTables(catalog, null, tablePattern, new String[]{"TABLE"})) {
            while (rs.next()) {
                list.add(rs.getString("TABLE_NAME"));
            }
        }
        return list;
    }

    @Override
    public List<String> listColumns(Connection conn, String schemaPattern, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        // schemaPattern 是数据库名(catalog)，不是 SQL Server schema
        // 如果指定了数据库名，使用三段式查询；否则查询当前数据库
        String sql;
        if (schemaPattern != null && !schemaPattern.isEmpty()) {
            sql = "SELECT COLUMN_NAME FROM [" + schemaPattern + "].INFORMATION_SCHEMA.COLUMNS " +
                  "WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        } else {
            sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                  "WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        return cols;
    }

    @Override
    public List<String> listPrimaryKeys(Connection conn, String schemaPattern, String table) throws SQLException {
        List<String> pk = new ArrayList<>();
        // schemaPattern 是数据库名(catalog)，不是 SQL Server schema
        String sql;
        if (schemaPattern != null && !schemaPattern.isEmpty()) {
            sql = "SELECT COLUMN_NAME FROM [" + schemaPattern + "].INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
                  "WHERE TABLE_NAME = ? AND CONSTRAINT_NAME LIKE 'PK_%' " +
                  "ORDER BY ORDINAL_POSITION";
        } else {
            sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
                  "WHERE TABLE_NAME = ? AND CONSTRAINT_NAME LIKE 'PK_%' " +
                  "ORDER BY ORDINAL_POSITION";
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pk.add(rs.getString(1));
                }
            }
        }
        return pk;
    }

    @Override
    public String buildSelectFull(String schema, String table, String timestampField, List<String> pkColumns) {
        // schema 是数据库名(catalog)，需要使用三段式 [database].[dbo].[table]
        String tableName = buildTableName(schema, table);

        String order = "[" + timestampField + "]";
        if (!pkColumns.isEmpty()) {
            order += ", [" + pkColumns.get(0) + "]";
        }

        return "SELECT * FROM " + tableName + " ORDER BY " + order;
    }

    @Override
    public boolean supportsTimestampWindowFullSync() {
        return true;
    }

    @Override
    public String buildSelectTimestampBounds(String schema, String table, String timestampField) {
        String tableName = buildTableName(schema, table);
        return "SELECT MIN([" + timestampField + "]), MAX([" + timestampField + "]) FROM "
            + tableName + " WHERE [" + timestampField + "] IS NOT NULL";
    }

    @Override
    public String buildSelectFullWindow(String schema, String table, String timestampField, List<String> pkColumns) {
        String tableName = buildTableName(schema, table);
        return "SELECT * FROM " + tableName + " WHERE ["
            + timestampField + "] >= ? AND [" + timestampField + "] < ?";
    }

    @Override
    public String buildSelectIncremental(String schema, String table, String timestampField, List<String> pkColumns) {
        // schema 是数据库名(catalog)，需要使用三段式 [database].[dbo].[table]
        String tableName = buildTableName(schema, table);

        String order = "[" + timestampField + "]";
        if (!pkColumns.isEmpty()) {
            order += ", [" + pkColumns.get(0) + "]";
        }

        return "SELECT * FROM " + tableName + " WHERE [" + timestampField + "] > ? ORDER BY " + order;
    }

    private String buildTableName(String schema, String table) {
        if (schema != null && !schema.isEmpty()) {
            return "[" + schema + "].[dbo].[" + table + "]";
        }
        return "[dbo].[" + table + "]";
    }
}
