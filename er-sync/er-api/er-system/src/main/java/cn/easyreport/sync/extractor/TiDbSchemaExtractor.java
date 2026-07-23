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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * TiDB schema extractor. 独立实现，便于后续针对 TiDB 的差异化适配。
 */
public class TiDbSchemaExtractor implements SchemaExtractor {

    private static final Logger log = LoggerFactory.getLogger(TiDbSchemaExtractor.class);
    private final ErDatasource ds;

    public TiDbSchemaExtractor(ErDatasource ds) {
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
            // 将逗号分隔的多个模式拆分
            List<String> schemas = splitPattern(schemaPattern);
            List<String> tables = splitPattern(tablePattern);

            // 先用 information_schema 查出所有匹配的 (schema, table) 对
            // MySQL/TiDB 的 JDBC catalog 参数不支持 % 通配符，information_schema 更可靠
            List<String[]> pairs = queryByInformationSchema(conn, schemas, tables);

            // 对每一对 (schema, table) 获取完整元数据
            for (String[] pair : pairs) {
                list.add(getTableInternal(conn, pair[0], pair[1]));
            }
        }
        return list;
    }

    /**
     * 通过 information_schema 查询匹配的 (schema, table) 对，支持 % 通配符和逗号分隔多值
     */
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
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql.toString())) {
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

    private TableMeta getTableInternal(Connection conn, String schema, String table) throws Exception {
        TableMeta meta = new TableMeta();
        meta.setSchema(schema);
        meta.setName(table);

        // table options
        try (PreparedStatement ps = conn.prepareStatement(
            "select ENGINE, TABLE_COLLATION, TABLE_COMMENT from information_schema.tables where table_schema=? and table_name=?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    meta.setEngine(rs.getString("ENGINE"));
                    String collation = rs.getString("TABLE_COLLATION");
                    if (collation != null) {
                        int idx = collation.indexOf("_");
                        if (idx > 0) {
                            meta.setCharset(collation.substring(0, idx));
                        }
                    }
                    meta.setComment(rs.getString("TABLE_COMMENT"));
                }
            }
        }

        // columns
        List<ColumnMeta> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "select COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT, ORDINAL_POSITION, CHARACTER_SET_NAME, COLLATION_NAME " +
                "from information_schema.columns where table_schema=? and table_name=? order by ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnMeta c = new ColumnMeta();
                    c.setName(rs.getString("COLUMN_NAME"));
                    c.setColumnType(rs.getString("COLUMN_TYPE"));
                    c.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                    c.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    c.setAutoIncrement(Optional.ofNullable(rs.getString("EXTRA")).orElse("").toLowerCase().contains("auto_increment"));
                    c.setComment(rs.getString("COLUMN_COMMENT"));
                    c.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                    c.setCharacterSet(rs.getString("CHARACTER_SET_NAME"));
                    c.setCollation(rs.getString("COLLATION_NAME"));
                    columns.add(c);
                }
            }
        }
        meta.setColumns(columns);

        // primary key
        List<String> pk = new ArrayList<>();
        DatabaseMetaData dbMeta = conn.getMetaData();
        // TiDB中，catalog对应database，schema为null
        try (ResultSet rs = dbMeta.getPrimaryKeys(schema, null, table)) {
            Map<Short, String> ordered = new TreeMap<>();
            while (rs.next()) {
                ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
            pk.addAll(ordered.values());
        }
        meta.setPrimaryKey(pk);

        // indexes (excluding PK)
        Map<String, IndexMeta> indexMap = new LinkedHashMap<>();
        // TiDB中，catalog对应database，schema为null
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
