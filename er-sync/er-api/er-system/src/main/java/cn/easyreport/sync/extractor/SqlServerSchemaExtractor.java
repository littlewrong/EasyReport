package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * SQL Server schema extractor.
 */
public class SqlServerSchemaExtractor implements SchemaExtractor {

    private final ErDatasource datasource;

    public SqlServerSchemaExtractor(ErDatasource datasource) {
        this.datasource = datasource;
    }

    @Override
    public TableMeta getTable(String schemaPattern, String tableName) throws Exception {
        List<TableMeta> tables = listTables(schemaPattern, tableName);
        return tables.isEmpty() ? null : tables.get(0);
    }

    @Override
    public List<TableMeta> listTables(String schemaPattern, String tablePattern) throws Exception {
        List<TableMeta> tables = new ArrayList<>();
        // SQL Server: schemaPattern 是数据库名(catalog)，schema 固定为 dbo
        // % 不是合法的数据库名字符，去掉通配符后按逗号拆分
        List<String> catalogs = splitPattern(schemaPattern);
        List<String> tablePatterns = splitPattern(tablePattern);

        try (Connection conn = DriverManager.getConnection(
                datasource.getJdbcUrl(),
                datasource.getUsername(),
                datasource.getPassword())) {

            DatabaseMetaData meta = conn.getMetaData();
            // 如果没有指定库名，只查一次（当前连接的库）
            if (catalogs.isEmpty()) catalogs.add(null);

            for (String catalog : catalogs) {
                // 去掉通配符，SQL Server 库名不支持模糊匹配
                String cleanCatalog = catalog != null ? catalog.replace("%", "").replace("_", "") : null;
                if (cleanCatalog != null && cleanCatalog.isEmpty()) cleanCatalog = null;

                if (cleanCatalog != null) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("USE [" + cleanCatalog + "]");
                    } catch (Exception ignore) { continue; }
                }

                List<String> patternsToQuery = tablePatterns.isEmpty()
                    ? java.util.Collections.singletonList("%") : tablePatterns;

                for (String tp : patternsToQuery) {
                    try (ResultSet rs = meta.getTables(cleanCatalog, "dbo", tp, new String[]{"TABLE"})) {
                        while (rs.next()) {
                            String tableName = rs.getString("TABLE_NAME");
                            String tableSchema = rs.getString("TABLE_SCHEM");

                            TableMeta tableMeta = new TableMeta();
                            tableMeta.setName(tableName);
                            tableMeta.setSchema(tableSchema != null ? tableSchema : cleanCatalog);
                            tableMeta.setComment(rs.getString("REMARKS"));
                            tableMeta.setColumns(extractColumns(conn, tableSchema, tableName));
                            tableMeta.setPrimaryKey(extractPrimaryKey(meta, tableSchema, tableName));
                            tableMeta.setIndexes(extractIndexes(meta, tableSchema, tableName));
                            tables.add(tableMeta);
                        }
                    }
                }
            }
        }

        return tables;
    }

    @Override
    public List<TableMeta> listTablesUsingFirstSchema(String schemaPattern, String tablePattern) throws Exception {
        return listFirstSchemaTables(schemaPattern, tablePattern, true);
    }

    @Override
    public List<TableMeta> listTableNamesUsingFirstSchema(String schemaPattern, String tablePattern) throws Exception {
        return listFirstSchemaTables(schemaPattern, tablePattern, false);
    }

    private List<TableMeta> listFirstSchemaTables(String schemaPattern, String tablePattern, boolean includeMetadata) throws Exception {
        Class.forName(datasource.getDriverClass());
        try (Connection conn = DriverManager.getConnection(datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword())) {
            FirstScopeDiscovery discovery = discoverFirstScope(conn, schemaPattern, tablePattern);
            List<TableMeta> templates = new ArrayList<>();
            if (!discovery.scopes.isEmpty()) {
                String catalog = discovery.scopes.get(0);
                for (String tableName : discovery.tables) {
                    TableMeta table = new TableMeta();
                    table.setDatabase(catalog);
                    table.setSchema("dbo");
                    table.setName(tableName);
                    if (includeMetadata) {
                        populateCatalogTable(conn, table);
                    }
                    templates.add(table);
                }
            }
            return FirstSchemaSupport.expand(discovery.scopes, templates, includeMetadata, true);
        }
    }

    private FirstScopeDiscovery discoverFirstScope(Connection conn, String schemaPattern, String tablePattern) throws Exception {
        List<String> schemas = splitPattern(schemaPattern);
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT name FROM sys.databases WHERE state=0 AND HAS_DBACCESS(name)=1");
        if (schemas.isEmpty()) {
            // 默认仅选择连接当前数据库，避免空模式意外扩大为服务器上的所有数据库。
            String catalog = conn.getCatalog();
            if (catalog == null || catalog.trim().isEmpty()) {
                throw new IllegalArgumentException("SQL Server 源 Schema 为空时，必须在连接中配置默认数据库");
            }
            sql.append(" AND name = ?");
            params.add(catalog);
        } else {
            appendFirstScopePatterns(sql, params, "name", schemas);
        }
        sql.append(" ORDER BY name");
        List<String> scopes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindFirstScopePatterns(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) scopes.add(rs.getString("name"));
            }
        }
        List<String> tableNames = new ArrayList<>();
        if (scopes.isEmpty()) return new FirstScopeDiscovery(scopes, tableNames);

        String firstCatalog = scopes.get(0);
        conn.setCatalog(firstCatalog);
        sql = new StringBuilder("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
            + "WHERE TABLE_TYPE='BASE TABLE' AND TABLE_CATALOG=? AND TABLE_SCHEMA='dbo'");
        params = new ArrayList<>();
        params.add(firstCatalog);
        appendFirstScopePatterns(sql, params, "TABLE_NAME", splitPattern(tablePattern));
        sql.append(" ORDER BY TABLE_NAME");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindFirstScopePatterns(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tableNames.add(rs.getString("TABLE_NAME"));
            }
        }
        if (tableNames.isEmpty()) {
            throw new IllegalStateException("首个匹配数据库 " + firstCatalog
                + " 的 dbo 未匹配到任何表，请检查源表模式或调整源 Schema 范围");
        }
        return new FirstScopeDiscovery(scopes, tableNames);
    }

    private void populateCatalogTable(Connection conn, TableMeta table) throws SQLException {
        String catalog = table.getDatabase();
        String schema = table.getSchema();
        String name = table.getName();
        String commentSql = "SELECT CAST(ep.value AS nvarchar(max)) AS REMARKS FROM sys.tables t "
            + "JOIN sys.schemas s ON s.schema_id=t.schema_id "
            + "LEFT JOIN sys.extended_properties ep ON ep.class=1 AND ep.major_id=t.object_id "
            + "AND ep.minor_id=0 AND ep.name='MS_Description' WHERE s.name=? AND t.name=?";
        try (PreparedStatement ps = conn.prepareStatement(commentSql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) table.setComment(rs.getString("REMARKS"));
            }
        }

        DatabaseMetaData metadata = conn.getMetaData();
        List<ColumnMeta> columns = new ArrayList<>();
        String escape = metadata.getSearchStringEscape();
        String tablePattern = escape == null || escape.isEmpty() ? name
            : name.replace(escape, escape + escape).replace("%", escape + "%").replace("_", escape + "_");
        try (ResultSet rs = metadata.getColumns(catalog, schema, tablePattern, "%")) {
            while (rs.next()) {
                ColumnMeta column = new ColumnMeta();
                column.setName(rs.getString("COLUMN_NAME"));
                column.setColumnType(formatSqlServerType(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS")));
                column.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                column.setDefaultValue(normalizeSqlServerDefault(rs.getString("COLUMN_DEF")));
                column.setComment(rs.getString("REMARKS"));
                column.setAutoIncrement("YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")));
                column.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                columns.add(column);
            }
        }
        columns.sort(Comparator.comparing(ColumnMeta::getOrdinalPosition));
        table.setColumns(columns);

        SortedMap<Integer, String> primaryKey = new TreeMap<>();
        Set<String> primaryIndexNames = new HashSet<>();
        try (ResultSet rs = metadata.getPrimaryKeys(catalog, schema, name)) {
            while (rs.next()) {
                primaryKey.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                String primaryName = rs.getString("PK_NAME");
                if (primaryName != null) primaryIndexNames.add(primaryName);
            }
        }
        table.setPrimaryKey(new ArrayList<>(primaryKey.values()));

        Map<String, IndexMeta> indexes = new LinkedHashMap<>();
        Map<String, SortedMap<Integer, String>> indexColumns = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getIndexInfo(catalog, schema, name, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null || primaryIndexNames.contains(indexName)) continue;
                IndexMeta index = indexes.get(indexName);
                if (index == null) {
                    index = new IndexMeta();
                    index.setName(indexName);
                    index.setUnique(!rs.getBoolean("NON_UNIQUE"));
                    indexes.put(indexName, index);
                }
                indexColumns.computeIfAbsent(indexName, key -> new TreeMap<>())
                    .put(rs.getInt("ORDINAL_POSITION"), columnName);
            }
        }
        for (Map.Entry<String, IndexMeta> entry : indexes.entrySet()) {
            entry.getValue().setColumns(new ArrayList<>(indexColumns.get(entry.getKey()).values()));
        }
        table.setIndexes(new ArrayList<>(indexes.values()));
    }

    private void appendFirstScopePatterns(StringBuilder sql, List<String> params, String column, List<String> patterns) {
        if (patterns.isEmpty()) return;
        sql.append(" AND (");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append(column).append(" LIKE ? ESCAPE '\\'");
            params.add(patterns.get(i));
        }
        sql.append(")");
    }

    private void bindFirstScopePatterns(PreparedStatement ps, List<String> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
    }

    private static class FirstScopeDiscovery {
        private final List<String> scopes;
        private final List<String> tables;

        private FirstScopeDiscovery(List<String> scopes, List<String> tables) {
            this.scopes = scopes;
            this.tables = tables;
        }
    }

    private List<String> splitPattern(String pattern) {
        List<String> result = new ArrayList<>();
        if (pattern == null || pattern.trim().isEmpty()) return result;
        for (String p : pattern.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private List<ColumnMeta> extractColumns(Connection conn, String schema, String tableName) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();

        try (ResultSet rs = meta.getColumns(null, schema, tableName, "%")) {
            while (rs.next()) {
                ColumnMeta col = new ColumnMeta();
                col.setName(rs.getString("COLUMN_NAME"));

                // Get column type
                String typeName = rs.getString("TYPE_NAME");
                int columnSize = rs.getInt("COLUMN_SIZE");
                int decimalDigits = rs.getInt("DECIMAL_DIGITS");

                col.setColumnType(formatSqlServerType(typeName, columnSize, decimalDigits));
                col.setNullable("YES".equals(rs.getString("IS_NULLABLE")));
                col.setDefaultValue(normalizeSqlServerDefault(rs.getString("COLUMN_DEF")));
                col.setComment(rs.getString("REMARKS"));

                // Check if auto-increment (identity column in SQL Server)
                col.setAutoIncrement("YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT")));

                columns.add(col);
            }
        }

        return columns;
    }

    /**
     * 剥离 SQL Server 默认值的外层括号包裹。
     * SQL Server 存储默认值时会加双括号，如 ((0.00))、((1))、(getdate())、(N'hello')。
     * 逐层去掉最外层括号，直到不再以 '(' 开头且以 ')' 结尾。
     */
    private String normalizeSqlServerDefault(String def) {
        if (def == null) return null;
        String s = def.trim();
        // 逐层剥掉最外层匹配括号
        while (s.startsWith("(") && s.endsWith(")")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // 去掉 SQL Server NVARCHAR 字面量前缀 N，如 N'hello' -> 'hello'
        if (s.startsWith("N'") && s.endsWith("'")) {
            s = s.substring(1);
        }
        return s.isEmpty() ? null : s;
    }

    private String formatSqlServerType(String typeName, int columnSize, int decimalDigits) {
        typeName = typeName.toUpperCase();

        // SQL Server JDBC 驱动对 IDENTITY 列在 TYPE_NAME 中附加 " IDENTITY" 后缀（如 "bigint identity"）
        // 去掉后缀，IDENTITY 属性通过 IS_AUTOINCREMENT 字段单独处理
        if (typeName.endsWith(" IDENTITY")) {
            typeName = typeName.substring(0, typeName.length() - " IDENTITY".length());
        }

        switch (typeName) {
            case "CHAR":
            case "VARCHAR":
            case "NCHAR":
            case "NVARCHAR":
                return columnSize == -1 || columnSize == 2147483647
                    ? typeName + "(MAX)"
                    : typeName + "(" + columnSize + ")";
            case "BINARY":
            case "VARBINARY":
                return columnSize == -1 || columnSize == 2147483647
                    ? typeName + "(MAX)"
                    : typeName + "(" + columnSize + ")";
            case "DECIMAL":
            case "NUMERIC":
                return typeName + "(" + columnSize + "," + decimalDigits + ")";
            case "FLOAT":
            case "REAL":
                return typeName;
            default:
                return typeName;
        }
    }

    private List<String> extractPrimaryKey(DatabaseMetaData meta, String schema, String tableName) throws SQLException {
        List<String> pkColumns = new ArrayList<>();

        try (ResultSet rs = meta.getPrimaryKeys(null, schema, tableName)) {
            while (rs.next()) {
                pkColumns.add(rs.getString("COLUMN_NAME"));
            }
        }

        return pkColumns;
    }

    private List<IndexMeta> extractIndexes(DatabaseMetaData meta, String schema, String tableName) throws SQLException {
        List<IndexMeta> indexes = new ArrayList<>();

        try (ResultSet rs = meta.getIndexInfo(null, schema, tableName, false, false)) {
            String currentIndexName = null;
            IndexMeta currentIndex = null;

            while (rs.next()) {
                boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");

                if (indexName == null || columnName == null) {
                    continue;
                }

                // Skip primary key index
                if (indexName.startsWith("PK_")) {
                    continue;
                }

                if (!indexName.equals(currentIndexName)) {
                    if (currentIndex != null) {
                        indexes.add(currentIndex);
                    }
                    currentIndex = new IndexMeta();
                    currentIndex.setName(indexName);
                    currentIndex.setUnique(!nonUnique);
                    currentIndex.setColumns(new ArrayList<>());
                    currentIndexName = indexName;
                }

                if (currentIndex != null) {
                    currentIndex.getColumns().add(columnName);
                }
            }

            if (currentIndex != null) {
                indexes.add(currentIndex);
            }
        }

        return indexes;
    }
}
