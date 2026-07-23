package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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
