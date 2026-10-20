package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * TiDB schema extractor. 独立实现，便于后续针对 TiDB 的差异化适配。
 */
public class TiDbSchemaExtractor implements SchemaExtractor {

    private static final Logger log = LoggerFactory.getLogger(TiDbSchemaExtractor.class);
    private static final String TABLE_METADATA_COLUMNS =
        "table_schema, table_name, ENGINE, TABLE_COLLATION, TABLE_COMMENT";
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
        long started = System.nanoTime();
        MetadataFilter filter = patternFilter(schemaPattern, tablePattern);
        List<TableMeta> tables;
        try (Connection conn = open()) {
            tables = queryTables(conn, filter);
            log.info("[TiDbSchemaExtractor] Found {} tables, discovery took {} ms", tables.size(), elapsedMillis(started));
            if (!tables.isEmpty()) {
                populateMetadata(conn, tables, filter, true);
            }
        }
        log.info("[TiDbSchemaExtractor] Collected metadata for {} tables across {} schemas in {} ms",
            tables.size(), indexTables(tables).size(), elapsedMillis(started));
        return tables;
    }

    @Override
    public List<TableMeta> listTablesUsingFirstSchema(String schemaPattern, String tablePattern) throws Exception {
        long started = System.nanoTime();
        try (Connection conn = open()) {
            FirstSchemaDiscovery discovery = discoverFirstSchema(conn, schemaPattern, tablePattern);
            if (discovery.schemas.isEmpty()) {
                return new ArrayList<>();
            }
            populateMetadata(conn, discovery.tables, templateFilter(discovery.firstSchema(), tablePattern), true);
            List<TableMeta> result = FirstSchemaSupport.expand(discovery.schemas, discovery.tables, true, false);
            log.info("[TiDbSchemaExtractor] First schema {}, matched schemas {}, template tables {}, expanded tables {}, total {} ms",
                discovery.firstSchema(), discovery.schemas.size(), discovery.tables.size(), result.size(), elapsedMillis(started));
            return result;
        }
    }

    /**
     * 分片模式预览与执行复用库/表发现逻辑，只读取首库的表清单，不读取列和索引。
     */
    @Override
    public List<TableMeta> listTableNamesUsingFirstSchema(String schemaPattern, String tablePattern) throws Exception {
        long started = System.nanoTime();
        try (Connection conn = open()) {
            FirstSchemaDiscovery discovery = discoverFirstSchema(conn, schemaPattern, tablePattern);
            List<TableMeta> result = FirstSchemaSupport.expand(discovery.schemas, discovery.tables, false, false);
            log.info("[TiDbSchemaExtractor] Preview first schema {}, matched schemas {}, template tables {}, expanded tables {}, total {} ms",
                discovery.firstSchema(), discovery.schemas.size(), discovery.tables.size(), result.size(), elapsedMillis(started));
            return result;
        }
    }

    private FirstSchemaDiscovery discoverFirstSchema(Connection conn, String schemaPattern, String tablePattern) throws Exception {
        long started = System.nanoTime();
        StringBuilder condition = new StringBuilder();
        List<String> params = new ArrayList<>();
        appendPatterns(condition, params, "schema_name", splitPattern(schemaPattern));
        MetadataFilter filter = new MetadataFilter(condition.toString(), params);
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT schema_name FROM information_schema.schemata WHERE 1=1"
            + filter.condition + " ORDER BY schema_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            filter.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    schemas.add(rs.getString("schema_name"));
                }
            }
        }
        log.info("[TiDbSchemaExtractor] Matched {} schemas, schema discovery took {} ms", schemas.size(), elapsedMillis(started));
        if (schemas.isEmpty()) {
            return new FirstSchemaDiscovery(schemas, Collections.emptyList());
        }

        String firstSchema = schemas.get(0);
        started = System.nanoTime();
        List<TableMeta> tables = queryTables(conn, templateFilter(firstSchema, tablePattern));
        log.info("[TiDbSchemaExtractor] First schema {}, template tables {}, table discovery took {} ms",
            firstSchema, tables.size(), elapsedMillis(started));
        if (tables.isEmpty()) {
            throw new IllegalStateException("首个匹配库 " + firstSchema
                + " 未匹配到任何表，请检查源表模式或调整源 Schema 范围");
        }
        return new FirstSchemaDiscovery(schemas, tables);
    }

    /**
     * TABLES 同时返回表选项；详情各查询一次，避免旧版 TiDB 按批反复扫描全实例元数据。
     */
    private List<TableMeta> queryTables(Connection conn, MetadataFilter filter) throws Exception {
        List<TableMeta> result = new ArrayList<>();
        String sql = "SELECT " + TABLE_METADATA_COLUMNS
            + " FROM information_schema.tables WHERE table_type='BASE TABLE'"
            + filter.condition + " ORDER BY table_schema, table_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            filter.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableMeta meta = new TableMeta();
                    meta.setSchema(rs.getString("table_schema"));
                    meta.setName(rs.getString("table_name"));
                    readTableOptions(rs, meta);
                    result.add(meta);
                }
            }
        }
        return result;
    }

    @Override
    public TableMeta getTable(String schema, String table) throws Exception {
        try (Connection conn = open()) {
            TableMeta meta = new TableMeta();
            meta.setSchema(schema);
            meta.setName(table);
            MetadataFilter filter = new MetadataFilter(" AND table_schema=? AND table_name=?", Arrays.asList(schema, table));
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + TABLE_METADATA_COLUMNS + " FROM information_schema.tables WHERE 1=1" + filter.condition)) {
                filter.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        readTableOptions(rs, meta);
                    }
                }
            }
            // 保持单表查询的既有行为：不存在时返回空元数据，而不是抛出异常。
            populateMetadata(conn, Collections.singletonList(meta), filter, false);
            return meta;
        }
    }

    private void readTableOptions(ResultSet rs, TableMeta meta) throws Exception {
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

    private void populateMetadata(Connection conn, List<TableMeta> tables, MetadataFilter filter, boolean requireColumns) throws Exception {
        Map<String, Map<String, TableMeta>> tablesBySchema = indexTables(tables);
        long started = System.nanoTime();
        readColumns(conn, tablesBySchema, filter);
        long columnsMillis = elapsedMillis(started);
        started = System.nanoTime();
        readIndexes(conn, tablesBySchema, filter);
        log.info("[TiDbSchemaExtractor] Loaded details for {} tables, columns {} ms, indexes {} ms",
            tables.size(), columnsMillis, elapsedMillis(started));
        for (TableMeta meta : tables) {
            meta.getColumns().sort(Comparator.comparing(ColumnMeta::getOrdinalPosition));
            if (requireColumns && meta.getColumns().isEmpty()) {
                throw new IllegalStateException("无法读取表 " + meta.getSchema() + "." + meta.getName()
                    + " 的列元数据，表可能已被删除或发生权限变化，请检查后重试");
            }
        }
    }

    private void readColumns(Connection conn, Map<String, Map<String, TableMeta>> tables, MetadataFilter filter) throws Exception {
        String sql = "SELECT table_schema, table_name, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT, ORDINAL_POSITION, CHARACTER_SET_NAME, COLLATION_NAME "
            + "FROM information_schema.columns WHERE 1=1" + filter.condition
            + " ORDER BY table_schema, table_name, ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            filter.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableMeta meta = findTable(tables, rs);
                    if (meta == null) continue;
                    ColumnMeta c = new ColumnMeta();
                    c.setName(rs.getString("COLUMN_NAME"));
                    c.setColumnType(rs.getString("COLUMN_TYPE"));
                    c.setNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                    c.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    c.setAutoIncrement(Optional.ofNullable(rs.getString("EXTRA")).orElse("").toLowerCase(Locale.ROOT).contains("auto_increment"));
                    c.setComment(rs.getString("COLUMN_COMMENT"));
                    c.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                    c.setCharacterSet(rs.getString("CHARACTER_SET_NAME"));
                    c.setCollation(rs.getString("COLLATION_NAME"));
                    meta.getColumns().add(c);
                }
            }
        }
    }

    private void readIndexes(Connection conn, Map<String, Map<String, TableMeta>> tables, MetadataFilter filter) throws Exception {
        String sql = "SELECT table_schema, table_name, INDEX_NAME, NON_UNIQUE, COLUMN_NAME, SEQ_IN_INDEX "
            + "FROM information_schema.statistics WHERE 1=1" + filter.condition
            + " ORDER BY table_schema, table_name, INDEX_NAME, SEQ_IN_INDEX";
        Map<TableMeta, SortedMap<Integer, String>> primaryKeys = new HashMap<>();
        Map<TableMeta, Map<String, IndexColumns>> indexes = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            filter.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableMeta meta = findTable(tables, rs);
                    if (meta == null) continue;
                    String column = rs.getString("COLUMN_NAME");
                    if (column == null) continue;
                    String indexName = rs.getString("INDEX_NAME");
                    int position = rs.getInt("SEQ_IN_INDEX");
                    if ("PRIMARY".equalsIgnoreCase(indexName)) {
                        primaryKeys.computeIfAbsent(meta, key -> new TreeMap<>()).put(position, column);
                    } else {
                        Map<String, IndexColumns> tableIndexes = indexes.computeIfAbsent(meta, key -> new LinkedHashMap<>());
                        IndexColumns index = tableIndexes.get(nameKey(indexName));
                        if (index == null) {
                            index = new IndexColumns(indexName, !rs.getBoolean("NON_UNIQUE"));
                            tableIndexes.put(nameKey(indexName), index);
                        }
                        index.columns.put(position, column);
                    }
                }
            }
        }
        for (Map.Entry<TableMeta, SortedMap<Integer, String>> entry : primaryKeys.entrySet()) {
            entry.getKey().setPrimaryKey(new ArrayList<>(entry.getValue().values()));
        }
        for (Map.Entry<TableMeta, Map<String, IndexColumns>> entry : indexes.entrySet()) {
            for (IndexColumns index : entry.getValue().values()) {
                index.meta.setColumns(new ArrayList<>(index.columns.values()));
                entry.getKey().getIndexes().add(index.meta);
            }
        }
    }

    private Map<String, Map<String, TableMeta>> indexTables(List<TableMeta> tables) {
        Map<String, Map<String, TableMeta>> result = new LinkedHashMap<>();
        for (TableMeta meta : tables) {
            result.computeIfAbsent(nameKey(meta.getSchema()), key -> new LinkedHashMap<>())
                .put(nameKey(meta.getName()), meta);
        }
        return result;
    }

    private TableMeta findTable(Map<String, Map<String, TableMeta>> tables, ResultSet rs) throws Exception {
        Map<String, TableMeta> schema = tables.get(nameKey(rs.getString("table_schema")));
        return schema == null ? null : schema.get(nameKey(rs.getString("table_name")));
    }

    private String nameKey(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    private MetadataFilter patternFilter(String schemaPattern, String tablePattern) {
        StringBuilder condition = new StringBuilder();
        List<String> params = new ArrayList<>();
        appendPatterns(condition, params, "table_schema", splitPattern(schemaPattern));
        appendPatterns(condition, params, "table_name", splitPattern(tablePattern));
        return new MetadataFilter(condition.toString(), params);
    }

    private MetadataFilter templateFilter(String schema, String tablePattern) {
        StringBuilder condition = new StringBuilder(" AND table_schema=?");
        List<String> params = new ArrayList<>();
        params.add(schema);
        appendPatterns(condition, params, "table_name", splitPattern(tablePattern));
        return new MetadataFilter(condition.toString(), params);
    }

    private void appendPatterns(StringBuilder condition, List<String> params, String column, List<String> patterns) {
        if (patterns.isEmpty()) return;
        condition.append(" AND (");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) condition.append(" OR ");
            condition.append(column).append(" LIKE ?");
            params.add(patterns.get(i));
        }
        condition.append(")");
    }

    private List<String> splitPattern(String pattern) {
        List<String> result = new ArrayList<>();
        if (pattern == null || pattern.trim().isEmpty()) return result;
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static class MetadataFilter {
        private final String condition;
        private final List<String> params;

        private MetadataFilter(String condition, List<String> params) {
            this.condition = condition;
            this.params = params;
        }

        private void bind(PreparedStatement statement) throws Exception {
            for (int i = 0; i < params.size(); i++) {
                statement.setString(i + 1, params.get(i));
            }
        }
    }

    private static class IndexColumns {
        private final IndexMeta meta = new IndexMeta();
        private final SortedMap<Integer, String> columns = new TreeMap<>();

        private IndexColumns(String name, boolean unique) {
            meta.setName(name);
            meta.setUnique(unique);
        }
    }

    private static class FirstSchemaDiscovery {
        private final List<String> schemas;
        private final List<TableMeta> tables;

        private FirstSchemaDiscovery(List<String> schemas, List<TableMeta> tables) {
            this.schemas = schemas;
            this.tables = tables;
        }

        private String firstSchema() {
            return schemas.isEmpty() ? null : schemas.get(0);
        }
    }
}
