package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle Schema 提取器
 *
 * 从 Oracle 数据库提取表结构信息，包括列、主键、索引和注释
 * Oracle Schema = User，对应 MySQL 的 Database 概念
 */
public class OracleSchemaExtractor implements SchemaExtractor {

    private final ErDatasource ds;

    public OracleSchemaExtractor(ErDatasource ds) {
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
        // Oracle: schema = User(Owner)，大写存储；tablePattern 支持逗号分隔和 % 通配
        List<String> schemas = splitPattern(schemaPattern);
        List<String> tables = splitPattern(tablePattern);
        if (schemas.isEmpty()) {
            String defaultSchema = ds.getUsername() != null ? ds.getUsername().toUpperCase() : null;
            if (defaultSchema != null) schemas.add(defaultSchema);
        } else {
            schemas.replaceAll(String::toUpperCase);
        }
        tables.replaceAll(String::toUpperCase);

        try (Connection conn = openConnection()) {
            StringBuilder sql = new StringBuilder("SELECT OWNER, TABLE_NAME FROM ALL_TABLES WHERE 1=1");
            List<String> params = new ArrayList<>();
            if (!schemas.isEmpty()) {
                sql.append(" AND (");
                for (int i = 0; i < schemas.size(); i++) {
                    if (i > 0) sql.append(" OR ");
                    sql.append("OWNER LIKE ?");
                    params.add(schemas.get(i));
                }
                sql.append(")");
            }
            if (!tables.isEmpty()) {
                sql.append(" AND (");
                for (int i = 0; i < tables.size(); i++) {
                    if (i > 0) sql.append(" OR ");
                    sql.append("TABLE_NAME LIKE ?");
                    params.add(tables.get(i));
                }
                sql.append(")");
            }
            sql.append(" ORDER BY OWNER, TABLE_NAME");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String owner = rs.getString("OWNER");
                        String tableName = rs.getString("TABLE_NAME");
                        TableMeta meta = extractTable(conn, owner, tableName);
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

        // 获取表注释
        String commentSql = "SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ? AND TABLE_TYPE = 'TABLE'";
        try (PreparedStatement ps = conn.prepareStatement(commentSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    meta.setComment(rs.getString("COMMENTS"));
                }
            }
        }

        // 获取列信息
        List<ColumnMeta> columns = new ArrayList<>();
        String colSql = "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, " +
                        "NULLABLE, DATA_DEFAULT, COLUMN_ID " +
                        "FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";
        try (PreparedStatement ps = conn.prepareStatement(colSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMeta col = new ColumnMeta();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setColumnType(formatColumnType(rs));
                    col.setNullable("Y".equalsIgnoreCase(rs.getString("NULLABLE")));
                    String defaultValue = rs.getString("DATA_DEFAULT");
                    if (defaultValue != null) {
                        defaultValue = defaultValue.trim();
                        if (defaultValue.isEmpty() || "NULL".equalsIgnoreCase(defaultValue)) {
                            defaultValue = null;
                        }
                    }
                    col.setDefaultValue(defaultValue);
                    col.setOrdinalPosition(rs.getInt("COLUMN_ID"));
                    columns.add(col);
                }
            }
        }
        meta.setColumns(columns);

        // 获取列注释
        String colCommentSql = "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(colCommentSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    String comment = rs.getString("COMMENTS");
                    if (comment != null && !comment.isEmpty()) {
                        for (ColumnMeta col : columns) {
                            if (col.getName().equalsIgnoreCase(colName)) {
                                col.setComment(comment);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // 获取主键
        List<String> pkColumns = new ArrayList<>();
        String pkSql = "SELECT cc.COLUMN_NAME FROM ALL_CONSTRAINTS c " +
                       "JOIN ALL_CONS_COLUMNS cc ON c.OWNER = cc.OWNER AND c.CONSTRAINT_NAME = cc.CONSTRAINT_NAME " +
                       "WHERE c.OWNER = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'P' " +
                       "ORDER BY cc.POSITION";
        try (PreparedStatement ps = conn.prepareStatement(pkSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pkColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        meta.setPrimaryKey(pkColumns);

        // 获取索引（排除主键索引）
        List<IndexMeta> indexes = new ArrayList<>();
        String idxSql = "SELECT i.INDEX_NAME, i.UNIQUENESS, ic.COLUMN_NAME " +
                        "FROM ALL_INDEXES i " +
                        "JOIN ALL_IND_COLUMNS ic ON i.OWNER = ic.INDEX_OWNER AND i.INDEX_NAME = ic.INDEX_NAME AND i.TABLE_NAME = ic.TABLE_NAME " +
                        "WHERE i.OWNER = ? AND i.TABLE_NAME = ? " +
                        "AND NOT EXISTS (SELECT 1 FROM ALL_CONSTRAINTS c WHERE c.OWNER = i.OWNER AND c.INDEX_NAME = i.INDEX_NAME AND c.CONSTRAINT_TYPE = 'P') " +
                        "ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(idxSql)) {
            ps.setString(1, schema);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                String lastIndexName = null;
                IndexMeta currentIndex = null;
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    if (!indexName.equals(lastIndexName)) {
                        currentIndex = new IndexMeta();
                        currentIndex.setName(indexName);
                        currentIndex.setUnique("UNIQUE".equalsIgnoreCase(rs.getString("UNIQUENESS")));
                        currentIndex.setColumns(new ArrayList<>());
                        indexes.add(currentIndex);
                        lastIndexName = indexName;
                    }
                    currentIndex.getColumns().add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        meta.setIndexes(indexes);

        return meta;
    }

    /**
     * 格式化 Oracle 列类型
     *
     * Oracle 的类型系统：
     * - NUMBER(p,s): 精确数值，含精度和小数位
     * - NUMBER(p): 整数
     * - NUMBER: 无精度限制
     * - VARCHAR2(n): 变长字符串
     * - CHAR(n): 定长字符串
     * - DATE, TIMESTAMP 等直接使用
     */
    private String formatColumnType(ResultSet rs) throws SQLException {
        String dataType = rs.getString("DATA_TYPE");
        Integer dataLength = getInteger(rs, "DATA_LENGTH");
        Integer precision = getInteger(rs, "DATA_PRECISION");
        Integer scale = getInteger(rs, "DATA_SCALE");

        if ("NUMBER".equalsIgnoreCase(dataType)) {
            if (precision != null && scale != null && scale > 0) {
                return "NUMBER(" + precision + "," + scale + ")";
            } else if (precision != null) {
                return "NUMBER(" + precision + ")";
            } else {
                return "NUMBER";
            }
        } else if ("VARCHAR2".equalsIgnoreCase(dataType) || "NVARCHAR2".equalsIgnoreCase(dataType)) {
            return dataType.toUpperCase() + "(" + (dataLength != null ? dataLength : 255) + ")";
        } else if ("CHAR".equalsIgnoreCase(dataType) || "NCHAR".equalsIgnoreCase(dataType)) {
            return dataType.toUpperCase() + "(" + (dataLength != null ? dataLength : 1) + ")";
        } else if ("RAW".equalsIgnoreCase(dataType)) {
            return "RAW(" + (dataLength != null ? dataLength : 2000) + ")";
        } else if ("FLOAT".equalsIgnoreCase(dataType)) {
            if (precision != null) {
                return "FLOAT(" + precision + ")";
            }
            return "FLOAT";
        } else {
            return dataType.toUpperCase();
        }
    }

    private Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Connection openConnection() throws Exception {
        Class.forName(ds.getDriverClass());
        return DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword());
    }

    public void close() {
        // Connection is closed in try-with-resources
    }
}
