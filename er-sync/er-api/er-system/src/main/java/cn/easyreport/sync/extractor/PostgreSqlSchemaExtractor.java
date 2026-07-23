package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PostgreSqlSchemaExtractor implements SchemaExtractor {

    private final ErDatasource ds;

    public PostgreSqlSchemaExtractor(ErDatasource ds) {
        this.ds = ds;
    }

    @Override
    public TableMeta getTable(String schemaPattern, String tableName) throws Exception {
        List<TableMeta> tables = listTables(schemaPattern, tableName);
        return tables.isEmpty() ? null : tables.get(0);
    }

    @Override
    public List<TableMeta> listTables(String schemaPattern, String tablePattern) throws Exception {
        List<TableMeta> result = new ArrayList<>();
        List<String> schemas = splitPattern(schemaPattern);
        List<String> tables = splitPattern(tablePattern);
        // PostgreSQL 默认 schema 为 public
        if (schemas.isEmpty()) schemas.add("public");

        try (Connection conn = openConnection()) {
            StringBuilder sql = new StringBuilder(
                "SELECT table_schema, table_name FROM information_schema.tables WHERE table_type = 'BASE TABLE'");
            List<String> params = new ArrayList<>();
            sql.append(" AND (");
            for (int i = 0; i < schemas.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("table_schema LIKE ?");
                params.add(schemas.get(i));
            }
            sql.append(")");
            if (!tables.isEmpty()) {
                sql.append(" AND (");
                for (int i = 0; i < tables.size(); i++) {
                    if (i > 0) sql.append(" OR ");
                    sql.append("table_name LIKE ?");
                    params.add(tables.get(i));
                }
                sql.append(")");
            }
            sql.append(" ORDER BY table_schema, table_name");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String schema = rs.getString("table_schema");
                        String tableName = rs.getString("table_name");
                        TableMeta meta = extractTable(conn, schema, tableName);
                        if (meta != null) result.add(meta);
                    }
                }
            }
        }
        return result;
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

    private TableMeta extractTable(Connection conn, String schema, String tableName) throws Exception {
        TableMeta meta = new TableMeta();
        meta.setName(tableName);
        meta.setSchema(schema);

        // 获取列信息
        List<ColumnMeta> columns = new ArrayList<>();
        String colSql = "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable, column_default " +
                        "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement ps = conn.prepareStatement(colSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMeta col = new ColumnMeta();
                    col.setName(rs.getString("column_name"));
                    col.setColumnType(formatColumnType(rs));
                    col.setNullable("YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    col.setDefaultValue(normalizePostgresDefault(rs.getString("column_default")));
                    columns.add(col);
                }
            }
        }
        meta.setColumns(columns);

        // 获取主键
        List<String> pkColumns = new ArrayList<>();
        String pkSql = "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) " +
                       "WHERE i.indrelid = (? || '.' || ?)::regclass AND i.indisprimary ORDER BY array_position(i.indkey, a.attnum)";
        try (PreparedStatement ps = conn.prepareStatement(pkSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pkColumns.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            // 如果查询失败，尝试使用 DatabaseMetaData
            DatabaseMetaData dbMeta = conn.getMetaData();
            try (ResultSet rs = dbMeta.getPrimaryKeys(null, schema, tableName)) {
                while (rs.next()) {
                    pkColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        meta.setPrimaryKey(pkColumns);

        // 获取索引（简化版本）
        List<IndexMeta> indexes = new ArrayList<>();
        meta.setIndexes(indexes);

        return meta;
    }

    private String formatColumnType(ResultSet rs) throws SQLException {
        String dataType = rs.getString("data_type");
        Integer charLen = rs.getObject("character_maximum_length", Integer.class);
        Integer numPrec = rs.getObject("numeric_precision", Integer.class);
        Integer numScale = rs.getObject("numeric_scale", Integer.class);

        if ("character varying".equalsIgnoreCase(dataType) && charLen != null) {
            return "VARCHAR(" + charLen + ")";
        } else if (("character".equalsIgnoreCase(dataType) || "bpchar".equalsIgnoreCase(dataType)) && charLen != null) {
            return "CHAR(" + charLen + ")";
        } else if ("bpchar".equalsIgnoreCase(dataType)) {
            // bpchar without explicit length defaults to CHAR(1)
            return "CHAR(1)";
        } else if (("numeric".equalsIgnoreCase(dataType) || "decimal".equalsIgnoreCase(dataType)) && numPrec != null) {
            if (numScale != null && numScale > 0) {
                return dataType.toUpperCase() + "(" + numPrec + "," + numScale + ")";
            } else {
                return dataType.toUpperCase() + "(" + numPrec + ")";
            }
        } else {
            return dataType.toUpperCase();
        }
    }

    /**
     * 去除 PostgreSQL 默认值中的类型转换语法（如 'A'::varchar、0::integer）。
     * MySQL / StarRocks / SQL Server 等不认识 :: 转换语法。
     */
    private String normalizePostgresDefault(String def) {
        if (def == null) return null;
        String s = def.trim();
        // 去掉 ::typename 或 ::character varying 等尾部类型转换
        // 例如：'A'::character varying  ->  'A'
        //       0::integer              ->  0
        //       NULL::text              ->  (null)
        int castIdx = s.lastIndexOf("::");
        if (castIdx >= 0) {
            s = s.substring(0, castIdx).trim();
        }
        // 去掉已经是 NULL 字面量的默认值（让列直接没有默认值）
        if (s.equalsIgnoreCase("NULL") || s.isEmpty()) {
            return null;
        }
        // 剥去外层单引号：'A' -> A（Dialect 的 formatDefault 会重新加引号）
        // 同时将 PostgreSQL 的 '' 转义还原为 '
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s.isEmpty() ? null : s;
    }

    private Connection openConnection() throws Exception {
        Class.forName(ds.getDriverClass());
        return DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword());
    }

    public void close() {
        // Connection is closed in try-with-resources
    }
}
