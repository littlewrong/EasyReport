package cn.easyreport.sync.datasync;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * StarRocks数据同步提取器
 *
 * StarRocks兼容MySQL协议，使用相同的信息模式查询方式
 */
public class StarRocksDataSyncExtractor implements DataSyncExtractor {

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
        // StarRocks也支持information_schema.key_column_usage查询主键
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
        return "SELECT * FROM " + prefix + "`" + table + "`";
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
