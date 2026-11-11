package cn.easyreport.sync.datasync;

import cn.easyreport.sync.kingbase.KingbaseJdbcSupport;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** KingbaseES V9 source access uses JDBC metadata and retains actual identifier case. */
public class KingbaseDataSyncExtractor implements DataSyncExtractor {

    @Override
    public List<String> listTables(DatabaseMetaData meta, String dbType, String schemaPattern,
                                   String tablePattern) throws SQLException {
        String schema = schemaPattern;
        if (schema == null || schema.isEmpty()) {
            schema = KingbaseJdbcSupport.escapePattern(meta,
                KingbaseJdbcSupport.currentSchema(meta.getConnection()));
        }
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, schema, patternOrAll(tablePattern), new String[]{"TABLE"})) {
            while (rs.next()) {
                if (!isSystemSchema(rs.getString("TABLE_SCHEM"))) tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    @Override
    public List<String[]> listTableSpecs(Connection conn, List<String> schemaPatterns,
                                          List<String> tablePatterns) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        List<String> schemas = schemaPatterns.isEmpty()
            ? Collections.singletonList(KingbaseJdbcSupport.escapePattern(meta,
                KingbaseJdbcSupport.currentSchema(conn))) : schemaPatterns;
        List<String> tables = tablePatterns.isEmpty() ? Collections.singletonList("%") : tablePatterns;
        Map<String, String[]> result = new LinkedHashMap<>();
        for (String schema : schemas) {
            for (String table : tables) {
                try (ResultSet rs = meta.getTables(null, schema, patternOrAll(table), new String[]{"TABLE"})) {
                    while (rs.next()) {
                        String actualSchema = rs.getString("TABLE_SCHEM");
                        String actualTable = rs.getString("TABLE_NAME");
                        if (isSystemSchema(actualSchema)) continue;
                        if (actualSchema == null || actualSchema.isEmpty()) {
                            throw new SQLException("Kingbase table metadata did not return TABLE_SCHEM for " + actualTable);
                        }
                        result.put(actualSchema + '\0' + actualTable, new String[]{actualSchema, actualTable});
                    }
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    @Override
    public List<String> listColumns(Connection conn, String schema, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String actualSchema = resolveSchema(conn, schema);
        Map<Integer, String> ordered = new TreeMap<>();
        try (ResultSet rs = meta.getColumns(null, KingbaseJdbcSupport.escapePattern(meta, actualSchema),
                KingbaseJdbcSupport.escapePattern(meta, table), "%")) {
            while (rs.next()) ordered.put(rs.getInt("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(ordered.values());
    }

    @Override
    public List<String> listPrimaryKeys(Connection conn, String schema, String table) throws SQLException {
        Map<Integer, String> ordered = new TreeMap<>();
        try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, resolveSchema(conn, schema), table)) {
            while (rs.next()) ordered.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(ordered.values());
    }

    @Override
    public String buildSelectFull(String schema, String table, String timestampField, List<String> pkColumns) {
        return "SELECT * FROM " + qualify(schema, table) + orderBy(timestampField, pkColumns);
    }

    @Override
    public boolean supportsTimestampWindowFullSync() {
        return true;
    }

    @Override
    public String buildSelectTimestampBounds(String schema, String table, String timestampField) {
        String field = timestampColumn(timestampField);
        return "SELECT MIN(" + field + "), MAX(" + field + ") FROM " + qualify(schema, table)
            + " WHERE " + field + " IS NOT NULL";
    }

    @Override
    public String buildSelectFullWindow(String schema, String table, String timestampField, List<String> pkColumns) {
        String field = timestampColumn(timestampField);
        return "SELECT * FROM " + qualify(schema, table) + " WHERE " + field + " >= ? AND " + field + " < ?";
    }

    @Override
    public String buildSelectIncremental(String schema, String table, String timestampField, List<String> pkColumns) {
        return "SELECT * FROM " + qualify(schema, table) + " WHERE " + timestampColumn(timestampField)
            + " > ?" + orderBy(timestampField, pkColumns);
    }

    private static String qualify(String schema, String table) {
        return schema == null || schema.isEmpty() ? quote(table) : quote(schema) + "." + quote(table);
    }

    private static String quote(String identifier) {
        return KingbaseJdbcSupport.quoteIdentifier(identifier);
    }

    private static String timestampColumn(String field) {
        if (field == null || field.trim().isEmpty()) throw new IllegalArgumentException("timestamp field is required");
        return quote(field);
    }

    private static String orderBy(String timestampField, List<String> pkColumns) {
        Set<String> columns = new LinkedHashSet<>();
        if (timestampField != null && !timestampField.trim().isEmpty()) columns.add(timestampField);
        if (pkColumns != null) columns.addAll(pkColumns);
        List<String> quoted = new ArrayList<>();
        for (String column : columns) quoted.add(quote(column));
        return quoted.isEmpty() ? "" : " ORDER BY " + String.join(", ", quoted);
    }

    private static String resolveSchema(Connection conn, String schema) throws SQLException {
        return schema == null || schema.isEmpty() ? KingbaseJdbcSupport.currentSchema(conn) : schema;
    }

    private static String patternOrAll(String pattern) {
        return pattern == null || pattern.isEmpty() ? "%" : pattern;
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        String name = schema.toLowerCase(Locale.ROOT);
        return "information_schema".equals(name) || "sys_catalog".equals(name) || "pg_catalog".equals(name)
            || "sys_toast".equals(name) || "pg_toast".equals(name) || name.startsWith("pg_temp_")
            || name.startsWith("sys_temp_") || name.startsWith("pg_toast_temp_") || name.startsWith("sys_toast_temp_");
    }
}
