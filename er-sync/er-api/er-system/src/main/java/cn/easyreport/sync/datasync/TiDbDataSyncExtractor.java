package cn.easyreport.sync.datasync;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * TiDB extractor实现：目前与 MySQL 语法保持一致，但不再继承 MySql 实现，方便后续差异化。
 */
public class TiDbDataSyncExtractor implements DataSyncExtractor {

    @Override
    public List<String[]> listTableSpecs(Connection conn, List<String> schemaPatterns, List<String> tablePatterns) throws Exception {
        List<String[]> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT table_schema, table_name FROM information_schema.tables WHERE table_type='BASE TABLE'");
        List<String> params = new ArrayList<>();
        if (!schemaPatterns.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < schemaPatterns.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("table_schema LIKE ?");
                params.add(schemaPatterns.get(i));
            }
            sql.append(")");
        }
        if (!tablePatterns.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < tablePatterns.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("table_name LIKE ?");
                params.add(tablePatterns.get(i));
            }
            sql.append(")");
        }
        sql.append(" ORDER BY table_schema, table_name");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new String[]{rs.getString("table_schema"), rs.getString("table_name")});
                }
            }
        }
        return result;
    }

    @Override
    public List<String> listTables(DatabaseMetaData meta, String dbType, String schemaPattern, String tablePattern) throws SQLException {
        List<String> list = new ArrayList<>();
        try (ResultSet rs = meta.getTables(schemaPattern, null, tablePattern, new String[]{"TABLE"})) {
            while (rs.next()) {
                list.add(rs.getString("TABLE_NAME"));
            }
        }
        return list;
    }

    @Override
    public List<String> listColumns(Connection conn, String schemaPattern, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        String schema = (schemaPattern == null || schemaPattern.isEmpty()) ? conn.getCatalog() : schemaPattern;
        String sql = "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        }
        return cols;
    }

    @Override
    public List<String> listPrimaryKeys(Connection conn, String schemaPattern, String table) throws SQLException {
        List<String> pk = new ArrayList<>();
        String schema = (schemaPattern == null || schemaPattern.isEmpty()) ? conn.getCatalog() : schemaPattern;
        String sql = "SELECT COLUMN_NAME FROM information_schema.key_column_usage WHERE table_schema=? AND table_name=? AND constraint_name='PRIMARY' ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pk.add(rs.getString(1));
            }
        }
        return pk;
    }

    @Override
    public String buildSelectFull(String schema, String table, String tsField, List<String> pkCols) {
        String prefix = (schema != null && !schema.isEmpty()) ? ("`" + schema + "`.") : "";
        String order = "`" + tsField + "`";
        if (!pkCols.isEmpty()) order += ", `" + pkCols.get(0) + "`";
        return "SELECT * FROM " + prefix + "`" + table + "` ORDER BY " + order;
    }

    @Override
    public boolean supportsTimestampWindowFullSync() {
        return true;
    }

    @Override
    public String buildSelectTimestampBounds(String schema, String table, String tsField) {
        String prefix = (schema != null && !schema.isEmpty()) ? ("`" + schema + "`.") : "";
        return "SELECT MIN(`" + tsField + "`), MAX(`" + tsField + "`) FROM " + prefix + "`" + table + "` WHERE `" + tsField + "` IS NOT NULL";
    }

    @Override
    public String buildSelectFullWindow(String schema, String table, String tsField, List<String> pkCols) {
        String prefix = (schema != null && !schema.isEmpty()) ? ("`" + schema + "`.") : "";
        return "SELECT * FROM " + prefix + "`" + table + "` WHERE `" + tsField + "` >= ? AND `" + tsField + "` < ?";
    }

    @Override
    public String buildSelectIncremental(String schema, String table, String tsField, List<String> pkCols) {
        String prefix = (schema != null && !schema.isEmpty()) ? ("`" + schema + "`.") : "";
        String order = "`" + tsField + "`";
        if (!pkCols.isEmpty()) order += ", `" + pkCols.get(0) + "`";
        return "SELECT * FROM " + prefix + "`" + table + "` WHERE `" + tsField + "` > ? ORDER BY " + order;
    }
}
