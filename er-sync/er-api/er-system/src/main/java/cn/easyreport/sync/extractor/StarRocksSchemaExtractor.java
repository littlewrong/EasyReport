package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.util.*;

/**
 * StarRocks schema extractor using JDBC metadata + information_schema.
 *
 * StarRocks is compatible with MySQL protocol, so we can reuse similar extraction logic.
 */
public class StarRocksSchemaExtractor implements SchemaExtractor {

    private static final Logger log = LoggerFactory.getLogger(StarRocksSchemaExtractor.class);
    private final ErDatasource ds;

    public StarRocksSchemaExtractor(ErDatasource ds) {
        this.ds = ds;
    }

    private Connection open() throws Exception {
        Class.forName(ds.getDriverClass());
        return DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword());
    }

    @Override
    public List<TableMeta> listTables(String schemaPattern, String tablePattern) throws Exception {
        List<TableMeta> list = new ArrayList<>();
        try (Connection conn = open()) {
            List<String> schemas = splitPattern(schemaPattern);
            List<String> tables = splitPattern(tablePattern);
            List<String[]> pairs = queryByInformationSchema(conn, schemas, tables);
            for (String[] pair : pairs) {
                list.add(getTableInternal(conn, pair[0], pair[1]));
            }
        }
        return list;
    }

    private List<String[]> queryByInformationSchema(Connection conn, List<String> schemas, List<String> tables) throws Exception {
        List<String[]> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT table_schema, table_name FROM information_schema.tables WHERE table_type='BASE TABLE'");
        List<String> params = new ArrayList<>();
        if (!schemas.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < schemas.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("table_schema LIKE ?");
                params.add(schemas.get(i));
            }
            sql.append(")");
        }
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
                    result.add(new String[]{rs.getString("table_schema"), rs.getString("table_name")});
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

    @Override
    public TableMeta getTable(String schema, String table) throws Exception {
        try (Connection conn = open()) {
            return getTableInternal(conn, schema, table);
        }
    }

    /**
     * 将 StarRocks 内部存储类型规范化为标准 SQL 类型。
     * StarRocks information_schema 返回 decimal32/decimal64/decimal128 等内部类型，
     * 目标数据库不识别，需转换为标准 DECIMAL(p,s)。
     */
    private String normalizeStarRocksType(String columnType) {
        if (columnType == null) return null;
        // StarRocks information_schema 返回内部存储类型 decimal32/decimal64/decimal128，
        // 目标数据库不识别，统一转换为标准 DECIMAL(p,s)
        String normalized = columnType.replaceAll("(?i)decimal(?:32|64|128)\\((\\d+),\\s*(\\d+)\\)", "DECIMAL($1,$2)");
        // StarRocks BOOLEAN 列在 information_schema 中显示为 tinyint(1)，
        // 规范化为 BOOLEAN，避免映射到目标库 SMALLINT/TINYINT 后与 Java Boolean 值类型不兼容
        if ("tinyint(1)".equalsIgnoreCase(normalized)) {
            normalized = "BOOLEAN";
        }
        return normalized;
    }

    private TableMeta getTableInternal(Connection conn, String schema, String table) throws Exception {
        TableMeta meta = new TableMeta();
        meta.setSchema(schema);
        meta.setName(table);

        // table options - StarRocks也支持information_schema.tables
        try (PreparedStatement ps = conn.prepareStatement(
            "select ENGINE, TABLE_COMMENT from information_schema.tables where table_schema=? and table_name=?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    meta.setEngine(rs.getString("ENGINE"));
                    meta.setComment(rs.getString("TABLE_COMMENT"));
                    // StarRocks默认使用UTF-8
                    meta.setCharset("utf8");
                }
            }
        }

        // columns - 提取列信息
        List<ColumnMeta> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "select COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT, ORDINAL_POSITION " +
                "from information_schema.columns where table_schema=? and table_name=? order by ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMeta c = new ColumnMeta();
                    c.setName(rs.getString("COLUMN_NAME"));
                    c.setColumnType(normalizeStarRocksType(rs.getString("COLUMN_TYPE")));
                    c.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));

                    String defaultValue = rs.getString("COLUMN_DEFAULT");
                    c.setDefaultValue(defaultValue);

                    String extra = Optional.ofNullable(rs.getString("EXTRA")).orElse("").toLowerCase();
                    // StarRocks支持AUTO_INCREMENT
                    c.setAutoIncrement(extra.contains("auto_increment"));

                    c.setComment(rs.getString("COLUMN_COMMENT"));
                    c.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));

                    // StarRocks默认UTF-8
                    c.setCharacterSet("utf8");
                    c.setCollation("utf8_general_ci");

                    columns.add(c);
                }
            }
        }
        meta.setColumns(columns);

        // primary key
        List<String> pk = new ArrayList<>();
        DatabaseMetaData dbMeta = conn.getMetaData();
        // StarRocks同MySQL，catalog对应database
        try (ResultSet rs = dbMeta.getPrimaryKeys(schema, null, table)) {
            Map<Short, String> ordered = new TreeMap<>();
            while (rs.next()) {
                ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
            pk.addAll(ordered.values());
        }
        // StarRocks DUP_KEY / AGG_KEY 表没有传统主键，dbMeta.getPrimaryKeys() 返回空。
        // 尝试通过 information_schema.key_column_usage 查询 PRIMARY 约束
        if (pk.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.key_column_usage" +
                " WHERE table_schema=? AND table_name=? AND constraint_name='PRIMARY'" +
                " ORDER BY ordinal_position")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) pk.add(rs.getString(1));
                }
            } catch (Exception ignore) { /* 部分版本不支持，忽略 */ }
        }
        // 两种方法都找不到主键时，回退到第一列（与数据同步 executor 的 fallback 保持一致），
        // 确保目标库 DDL 中包含 PRIMARY KEY 约束，ON CONFLICT 才能正常工作。
        if (pk.isEmpty() && !columns.isEmpty()) {
            pk.add(columns.get(0).getName());
        }
        meta.setPrimaryKey(pk);

        // indexes (excluding PK)
        Map<String, IndexMeta> indexMap = new LinkedHashMap<>();
        try (ResultSet rs = dbMeta.getIndexInfo(schema, null, table, false, false)) {
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                String col = rs.getString("COLUMN_NAME");
                if (col == null) continue;
                if ("PRIMARY".equalsIgnoreCase(idxName)) {
                    continue;
                }
                IndexMeta idx = indexMap.computeIfAbsent(idxName, k -> {
                    IndexMeta m = new IndexMeta();
                    m.setName(k);
                    m.setUnique(!nonUnique);
                    return m;
                });
                idx.getColumns().add(col);
            }
        }
        meta.setIndexes(new ArrayList<>(indexMap.values()));

        return meta;
    }
}
