package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.kingbase.KingbaseJdbcSupport;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses Kingbase JDBC metadata rather than compatibility-mode-specific system catalogs. */
public class KingbaseSchemaExtractor implements SchemaExtractor {
    private static final Logger log = LoggerFactory.getLogger(KingbaseSchemaExtractor.class);
    private static final Pattern LITERAL_DEFAULT = Pattern.compile(
        "^('(?:[^']|'')*'|[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)|TRUE|FALSE|NULL)"
            + "(?:::[\\p{L}_][\\p{L}\\p{N}_$.]*(?:\\s+(?:VARYING|PRECISION))?"
            + "(?:\\s*\\(\\s*-?\\d+(?:\\s*,\\s*-?\\d+)?\\s*\\))?"
            + "(?:\\s+(?:WITH|WITHOUT)(?:\\s+LOCAL)?\\s+TIME\\s+ZONE)?(?:\\[\\])*)?$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private final ErDatasource datasource;

    public KingbaseSchemaExtractor(ErDatasource datasource) {
        this.datasource = datasource;
    }

    @Override
    public TableMeta getTable(String schema, String table) throws Exception {
        try (Connection connection = openConnection()) {
            String actualSchema = schema == null || schema.isEmpty() ? KingbaseJdbcSupport.currentSchema(connection) : schema;
            DatabaseMetaData metadata = connection.getMetaData();
            TableMeta result = null;
            try (ResultSet rows = metadata.getTables(connection.getCatalog(), KingbaseJdbcSupport.escapePattern(metadata, actualSchema),
                    KingbaseJdbcSupport.escapePattern(metadata, table), new String[]{"TABLE"})) {
                while (rows.next()) {
                    if (actualSchema.equals(rows.getString("TABLE_SCHEM")) && table.equals(rows.getString("TABLE_NAME"))) {
                        result = tableName(actualSchema, table, rows.getString("REMARKS"));
                        break;
                    }
                }
            }
            if (result != null) extractMetadata(connection, result);
            return result;
        }
    }

    /** Preview table names using the same schema resolution without fetching columns or indexes. */
    public List<TableMeta> listTableNames(String schemaPattern, String tablePattern) throws Exception {
        try (Connection connection = openConnection()) {
            return discoverTables(connection, matchingSchemas(connection, schemaPattern), tablePattern);
        }
    }

    @Override
    public List<TableMeta> listTables(String schemaPattern, String tablePattern) throws Exception {
        try (Connection connection = openConnection()) {
            List<TableMeta> result = discoverTables(connection, matchingSchemas(connection, schemaPattern), tablePattern);
            for (TableMeta table : result) extractMetadata(connection, table);
            return result;
        }
    }

    @Override
    public List<TableMeta> listTablesUsingFirstSchema(String schemaPattern, String tablePattern) throws Exception {
        return firstSchema(schemaPattern, tablePattern, true);
    }

    @Override
    public List<TableMeta> listTableNamesUsingFirstSchema(String schemaPattern, String tablePattern) throws Exception {
        return firstSchema(schemaPattern, tablePattern, false);
    }

    private List<TableMeta> firstSchema(String schemaPattern, String tablePattern, boolean includeMetadata) throws Exception {
        try (Connection connection = openConnection()) {
            List<String> schemas = matchingSchemas(connection, schemaPattern);
            if (schemas.isEmpty()) return Collections.emptyList();
            List<TableMeta> templates = discoverTables(connection, Collections.singletonList(schemas.get(0)), tablePattern);
            if (includeMetadata) {
                for (TableMeta table : templates) extractMetadata(connection, table);
            }
            return FirstSchemaSupport.expand(schemas, templates, includeMetadata, false);
        }
    }

    private List<String> matchingSchemas(Connection connection, String schemaPattern) throws SQLException {
        List<String> patterns = patterns(schemaPattern);
        if (patterns.isEmpty()) return Collections.singletonList(KingbaseJdbcSupport.currentSchema(connection));
        TreeSet<String> names = new TreeSet<>();
        try (ResultSet rows = connection.getMetaData().getSchemas()) {
            while (rows.next()) {
                String name = rows.getString("TABLE_SCHEM");
                if (name == null || isSystemSchema(name)) continue;
                for (String pattern : patterns) {
                    if (matches(name, pattern)) {
                        names.add(name);
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(names);
    }

    private List<TableMeta> discoverTables(Connection connection, List<String> schemas, String tablePattern) throws SQLException {
        List<String> patterns = patterns(tablePattern);
        if (patterns.isEmpty()) patterns.add("%");
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, TableMeta> tables = new TreeMap<>();
        for (String schema : schemas) {
            for (String pattern : patterns) {
                try (ResultSet rows = metadata.getTables(connection.getCatalog(), KingbaseJdbcSupport.escapePattern(metadata, schema),
                        pattern, new String[]{"TABLE"})) {
                    while (rows.next()) {
                        String actualSchema = rows.getString("TABLE_SCHEM");
                        String name = rows.getString("TABLE_NAME");
                        if (schema.equals(actualSchema) && name != null) {
                            tables.put(actualSchema + "\u0000" + name, tableName(actualSchema, name, rows.getString("REMARKS")));
                        }
                    }
                }
            }
        }
        return new ArrayList<>(tables.values());
    }

    private TableMeta tableName(String schema, String name, String comment) {
        TableMeta table = new TableMeta();
        table.setSchema(schema);
        table.setName(name);
        table.setComment(comment);
        return table;
    }

    private void extractMetadata(Connection connection, TableMeta table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        try (ResultSet rows = metadata.getColumns(catalog, KingbaseJdbcSupport.escapePattern(metadata, table.getSchema()),
                KingbaseJdbcSupport.escapePattern(metadata, table.getName()), "%")) {
            while (rows.next()) {
                ColumnMeta column = new ColumnMeta();
                column.setName(rows.getString("COLUMN_NAME"));
                column.setColumnType(formatColumnType(rows));
                column.setNullable(rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                String defaultValue = rows.getString("COLUMN_DEF");
                String autoIncrement;
                try {
                    autoIncrement = rows.getString("IS_AUTOINCREMENT");
                } catch (SQLException ignored) {
                    autoIncrement = null;
                }
                column.setAutoIncrement("YES".equalsIgnoreCase(autoIncrement)
                    || defaultValue != null && defaultValue.trim().matches("(?is)^(?:[a-z_][a-z_0-9]*\\.)?nextval\\s*\\(.*"));
                column.setDefaultValue(column.isAutoIncrement() ? null : normalizeDefault(defaultValue));
                if (!column.isAutoIncrement() && defaultValue != null) {
                    Matcher literal = LITERAL_DEFAULT.matcher(defaultValue.trim());
                    column.setDefaultValueLiteral(literal.matches() && literal.group(1).startsWith("'"));
                }
                column.setComment(rows.getString("REMARKS"));
                column.setOrdinalPosition(rows.getInt("ORDINAL_POSITION"));
                table.getColumns().add(column);
            }
        }
        table.getColumns().sort(Comparator.comparing(ColumnMeta::getOrdinalPosition));
        if (table.getColumns().isEmpty()) {
            throw new SQLException("无法读取金仓表 " + table.getSchema() + "." + table.getName() + " 的列元数据");
        }
        Map<Integer, String> keys = new TreeMap<>();
        String primaryKeyName = null;
        try (ResultSet rows = metadata.getPrimaryKeys(catalog, table.getSchema(), table.getName())) {
            while (rows.next()) {
                keys.put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
                primaryKeyName = rows.getString("PK_NAME");
            }
        }
        table.setPrimaryKey(new ArrayList<>(keys.values()));
        Map<String, IndexMeta> indexes = new TreeMap<>();
        Map<String, Map<Integer, String>> indexColumns = new TreeMap<>();
        java.util.Set<String> unsupportedIndexes = new java.util.HashSet<>();
        try (ResultSet rows = metadata.getIndexInfo(catalog, table.getSchema(), table.getName(), false, false)) {
            while (rows.next()) {
                if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || name.equals(primaryKeyName) || unsupportedIndexes.contains(name)) continue;
                String filter = rows.getString("FILTER_CONDITION");
                if (column == null || column.trim().isEmpty() || filter != null && !filter.trim().isEmpty()) {
                    // The shared IndexMeta model cannot represent expressions or partial predicates.
                    // Remove earlier columns as well, so a mixed index is never rebuilt with different semantics.
                    unsupportedIndexes.add(name);
                    indexes.remove(name);
                    indexColumns.remove(name);
                    log.warn("Skipping Kingbase expression/partial index {} on {}.{}: the shared index metadata model cannot represent it",
                        name, table.getSchema(), table.getName());
                    continue;
                }
                IndexMeta index = indexes.get(name);
                if (index == null) {
                    index = new IndexMeta();
                    index.setName(name);
                    index.setUnique(!rows.getBoolean("NON_UNIQUE"));
                    indexes.put(name, index);
                    indexColumns.put(name, new TreeMap<Integer, String>());
                }
                indexColumns.get(name).put(rows.getInt("ORDINAL_POSITION"), column);
            }
        }
        for (Map.Entry<String, IndexMeta> entry : indexes.entrySet()) {
            entry.getValue().setColumns(new ArrayList<>(indexColumns.get(entry.getKey()).values()));
            table.getIndexes().add(entry.getValue());
        }
    }

    private String formatColumnType(ResultSet rows) throws SQLException {
        String name = rows.getString("TYPE_NAME");
        if (name == null || name.trim().isEmpty()) throw new SQLException("金仓列缺少 TYPE_NAME 元数据");
        name = name.trim().toUpperCase(Locale.ROOT);
        int jdbcType = rows.getInt("DATA_TYPE");
        // Oracle-compatible DATE can include time; rely on the JDBC type when it does.
        if (jdbcType == Types.TIMESTAMP || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
            boolean timezone = jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || "TIMESTAMPTZ".equals(name) || name.contains("WITH TIME ZONE");
            int precision = rows.getInt("DECIMAL_DIGITS");
            String fractional = !rows.wasNull() && precision >= 0 && precision <= 6 ? "(" + precision + ")" : "";
            return "TIMESTAMP" + fractional + (timezone ? " WITH TIME ZONE" : "");
        }
        if (name.contains("(")) return name;
        if ("INT2".equals(name)) return "SMALLINT";
        if ("INT4".equals(name)) return "INTEGER";
        if ("INT8".equals(name)) return "BIGINT";
        if ("FLOAT4".equals(name)) return "REAL";
        if ("FLOAT8".equals(name)) return "DOUBLE PRECISION";
        if ("BOOL".equals(name)) return "BOOLEAN";
        if ("BPCHAR".equals(name) || "CHARACTER".equals(name)) name = "CHAR";
        if ("CHARACTER VARYING".equals(name)) name = "VARCHAR";
        int size = rows.getInt("COLUMN_SIZE");
        if (jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL) {
            int scale = rows.getInt("DECIMAL_DIGITS");
            boolean scaleUnknown = rows.wasNull();
            // Some drivers report the maximum representable display precision for unconstrained NUMERIC.
            // It is not a legal explicit precision; an explicitly parameterized TYPE_NAME was already retained above.
            return size > 0 && size <= 1000 ? name + "(" + size + (scaleUnknown ? "" : "," + scale) + ")" : name;
        }
        if (("VARCHAR".equals(name) || "CHAR".equals(name) || "NVARCHAR".equals(name) || "NCHAR".equals(name)
                || "VARCHAR2".equals(name) || "NVARCHAR2".equals(name) || "RAW".equals(name)) && size > 0 && size < Integer.MAX_VALUE) {
            return name + "(" + size + ")";
        }
        return name;
    }

    private String normalizeDefault(String value) {
        if (value == null) return null;
        String result = value.trim();
        Matcher literal = LITERAL_DEFAULT.matcher(result);
        if (literal.matches()) {
            result = literal.group(1);
            if ("NULL".equalsIgnoreCase(result)) return null;
            if (result.startsWith("'")) return result.substring(1, result.length() - 1).replace("''", "'");
        }
        return result;
    }

    private List<String> patterns(String value) {
        List<String> result = new ArrayList<>();
        if (value != null) {
            for (String pattern : value.split("[,;]")) {
                if (!pattern.trim().isEmpty()) result.add(pattern.trim());
            }
        }
        return result;
    }

    private boolean matches(String name, String pattern) {
        StringBuilder regex = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                regex.append(Pattern.quote(String.valueOf(c)));
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (escaped) regex.append(Pattern.quote("\\"));
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(name).matches();
    }

    private boolean isSystemSchema(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return "information_schema".equals(lower) || "sys_catalog".equals(lower) || "sys_toast".equals(lower)
            || "pg_catalog".equals(lower) || "pg_toast".equals(lower) || lower.startsWith("sys_temp_")
            || lower.startsWith("pg_temp_") || lower.startsWith("sys_toast_temp_") || lower.startsWith("pg_toast_temp_");
    }

    private Connection openConnection() throws Exception {
        Class.forName(datasource.getDriverClass());
        return DriverManager.getConnection(datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword());
    }
}
