package cn.easyreport.sync.datasync;

import cn.easyreport.sync.core.SyncProgressCallback;
import cn.easyreport.sync.domain.ErDatasource;

import java.net.URI;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads an existing, shared JDBC Catalog. It never creates or removes Catalog objects. */
final class StarRocksCatalogFullImport implements AutoCloseable {
    private static final Pattern TARGET = Pattern.compile("^`(?:``|[^`])+`\\.`(?:``|[^`])+`$");
    private static final Pattern TARGET_PARTS = Pattern.compile("^`((?:``|[^`])+)`\\.`((?:``|[^`])+)`$");
    private static final Pattern PROPERTY = Pattern.compile(
            "[\\\"']([^\\\"']+)[\\\"']\\s*=\\s*[\\\"']((?:\\\\.|[^\\\"'\\\\])*)[\\\"']");
    private final Connection target;
    private final String catalogTable;
    private final String targetTable;
    private final String selectColumns;
    private final String insertColumns;
    private final String timestampColumn;
    private final boolean oracle;
    private final Timestamp maxTimestamp;
    private final long sourceCount;
    private final LocalDate start;
    private final LocalDate end;
    private final WindowUnit unit;
    private final SyncProgressCallback callback;
    private final DataSyncCancellation cancellation;
    private boolean executed;
    private boolean closed;

    private StarRocksCatalogFullImport(Connection target, String catalogTable, String targetTable,
            String selectColumns, List<String> targetColumns, String timestampColumn,
            Timestamp maxTimestamp, long sourceCount, LocalDate start, LocalDate end, WindowUnit unit, boolean oracle,
            SyncProgressCallback callback, DataSyncCancellation cancellation) {
        this.target = target;
        this.catalogTable = catalogTable;
        // Keep the target in the internal Catalog even if the connection has a different default.
        this.targetTable = quote("default_catalog") + "." + targetTable;
        this.selectColumns = selectColumns;
        this.insertColumns = join(targetColumns);
        this.timestampColumn = timestampColumn;
        this.oracle = oracle;
        this.maxTimestamp = maxTimestamp;
        this.sourceCount = sourceCount;
        this.start = start;
        this.end = end;
        this.unit = unit;
        this.callback = callback;
        this.cancellation = cancellation;
    }

    static StarRocksCatalogFullImport prepare(ErDatasource datasource, Connection source, Connection target,
            String catalog, String schema, String table, String targetTable, List<String> sourceColumns,
            List<String> targetColumns, String timestampField, Integer thresholdWan, String windowUnit,
            SyncProgressCallback callback, DataSyncCancellation cancellation) throws Exception {
        stopped(cancellation);
        if (datasource == null) throw new IllegalArgumentException("Catalog 导入缺少源数据源");
        Dialect dialect = Dialect.of(datasource.getDatasourceType());
        if (catalog == null || !catalog.matches("[a-zA-Z][a-zA-Z0-9_]{0,1022}")
                || "default_catalog".equalsIgnoreCase(catalog)) {
            throw new IllegalArgumentException("请填写有效的外部 Catalog 名称，不能使用 default_catalog");
        }
        validateColumns(sourceColumns, targetColumns);
        if (targetTable == null || !TARGET.matcher(targetTable).matches()) {
            throw new IllegalArgumentException("Catalog 导入目标必须为内部数据库和表的限定名称");
        }
        int threshold = thresholdWan == null ? 50 : thresholdWan;
        if (threshold < 1) throw new IllegalArgumentException("Catalog 整批导入阈值必须为正整数，单位为万条");
        WindowUnit unit = WindowUnit.of(windowUnit);
        String scope = dialect.scope(source, datasource, schema);
        String actualTable = dialect == Dialect.ORACLE ? table.toUpperCase(Locale.ROOT) : table;
        String sourceTable = dialect.sourceTable(source, datasource, schema, scope, actualTable);
        String catalogTable = quote(catalog) + "." + quote(scope) + "." + quote(actualTable);
        String timestampColumn = null;
        if (timestampField != null && !timestampField.trim().isEmpty()) {
            for (String column : sourceColumns) {
                if (column.equalsIgnoreCase(timestampField.trim())) timestampColumn = column;
            }
            if (timestampColumn == null) throw new IllegalArgumentException("配置的时间戳字段不在源表字段中");
        }
        try {
            // The application and StarRocks may reach the same source through public/private
            // endpoints. Validate the logical mapping before any destructive work.
            Map<String, String> catalogProperties = verifyCatalog(target, source, datasource, dialect, catalog, schema, cancellation);
            if (dialect == Dialect.STARROCKS) verifyDistinctStarRocksTable(datasource, source, target,
                    catalogProperties, scope, actualTable, targetTable, cancellation);
            stopped(cancellation);
            Map<String, String> nativeTypes = nativeTypes(source, sourceTable, sourceColumns, dialect, cancellation);
            Projection projection = prepareProjection(target, catalogTable, quote("default_catalog") + "." + targetTable,
                    sourceColumns, targetColumns, nativeTypes, dialect, timestampColumn, cancellation);
            String filter = timestampColumn == null ? "" : " WHERE " + dialect.quote(timestampColumn) + " IS NOT NULL";
            boolean mysqlTimestamp = false;
            if (timestampColumn != null) {
                mysqlTimestamp = verifyTimestamp(source, sourceTable, dialect.quote(timestampColumn), dialect);
            }
            stopped(cancellation);
            long count;
            StringBuilder countSql = new StringBuilder("SELECT " + (dialect == Dialect.SQLSERVER ? "COUNT_BIG(*)" : "COUNT(*)"));
            for (int index : projection.jsonTextIndexes) {
                String column = sourceColumns.get(index);
                String length = dialect == Dialect.STARROCKS && "JSON".equals(nativeTypes.get(column.toLowerCase(Locale.ROOT)))
                        ? "LENGTH(JSON_STRING(" + dialect.quote(column) + "))" : dialect.utf8Length(dialect.quote(column));
                countSql.append(", MAX(").append(length).append(")");
            }
            countSql.append(" FROM ").append(sourceTable).append(filter);
            try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(countSql.toString())) {
                if (!rows.next()) throw new IllegalStateException("源表准确行数查询未返回结果，尚未清空目标表");
                count = rows.getLong(1);
                if (rows.wasNull() || count < 0) throw new IllegalStateException("源表准确行数无效，尚未清空目标表");
                for (int index = 0; index < projection.jsonTextIndexes.size(); index++) {
                    long bytes = rows.getLong(index + 2);
                    if (rows.wasNull()) bytes = 0;
                    long limit = projection.jsonTextLimits.get(index);
                    if (bytes < 0 || bytes > limit) {
                        throw new IllegalArgumentException("JSON 文本字段 " + sourceColumns.get(projection.jsonTextIndexes.get(index))
                                + " 的 UTF-8 安全字节数超出 Catalog/JSON 表达式容量（" + limit
                                + " 字节），尚未清空目标表；Oracle/SQL Server 使用保守字节上界");
                    }
                }
            }
            stopped(cancellation);
            Timestamp max = null;
            LocalDate start = null;
            LocalDate end = null;
            LocalDate minDay = null;
            LocalDate maxDay = null;
            if (timestampColumn != null && count > 0) {
                String ts = dialect.quote(timestampColumn);
                try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT MIN(" + ts + "), MAX(" + ts + ") FROM " + sourceTable + filter)) {
                    if (!rows.next()) throw new IllegalStateException("源时间戳范围查询未返回结果，尚未清空目标表");
                    Timestamp min = rows.getTimestamp(1);
                    max = rows.getTimestamp(2);
                    minDay = sourceDay(rows.getString(1));
                    maxDay = sourceDay(rows.getString(2));
                    if (min == null || max == null || min.after(max) || minDay.isAfter(maxDay)) {
                        throw new IllegalStateException("源时间戳范围无效，尚未清空目标表");
                    }
                    if (count >= threshold * 10_000L) {
                        if (projection.oracleTextTimestamp) {
                            throw new IllegalArgumentException("Oracle 分窗时间戳在 Catalog 中为文本，无法保证日期过滤正确下推；"
                                    + "请使用支持 oracle.temporal.to-datetime 的版本并将该字段映射为 DATETIME，尚未清空目标表");
                        }
                        start = unit.start(minDay);
                        end = unit.next(unit.start(maxDay));
                        if (start.getYear() < 1 || end.getYear() > 9999) {
                            throw new IllegalArgumentException("源时间戳无法构造有效的自然时间窗口，尚未清空目标表");
                        }
                    }
                }
            }
            stopped(cancellation);
            if (mysqlTimestamp && count > 0) {
                StarRocksCatalogSessionSettings.verifyTimestampSession(source, datasource, catalogProperties,
                        cancellation, minDay, maxDay);
            }
            StarRocksCatalogFullImport importer = new StarRocksCatalogFullImport(target, catalogTable,
                    targetTable, projection.sql, targetColumns, timestampColumn, max, count, start, end,
                    unit, dialect == Dialect.ORACLE, callback, cancellation);
            if (!projection.jsonTextIndexes.isEmpty()) StarRocksExternalFullImport.verifyJsonProjection(target, cancellation);
            importer.preflight(sourceColumns, targetColumns);
            return importer;
        } catch (SQLException failure) {
            stopped(cancellation);
            if (failure instanceof CatalogSQLException) throw failure;
            throw safe("Catalog 导入预校验失败，尚未清空目标表；请检查 Catalog、版本支持、权限、驱动和字段类型", failure);
        }
    }

    private static Projection prepareProjection(Connection connection, String catalogTable, String targetTable,
            List<String> sourceColumns, List<String> targetColumns, Map<String, String> nativeTypes,
            Dialect dialect, String timestampColumn, DataSyncCancellation cancellation) throws SQLException {
        Map<String, String> catalogTypes = describe(connection, catalogTable, cancellation);
        Map<String, String> targetTypes = describe(connection, targetTable, cancellation);
        List<String> expressions = new ArrayList<>();
        Projection projection = new Projection();
        for (int index = 0; index < sourceColumns.size(); index++) {
            String sourceType = catalogTypes.get(sourceColumns.get(index).toLowerCase(Locale.ROOT));
            String targetType = targetTypes.get(targetColumns.get(index).toLowerCase(Locale.ROOT));
            if (sourceType == null || targetType == null || sourceType.contains("UNKNOWN") || sourceType.contains("UNSUPPORTED")) {
                throw new IllegalArgumentException("Catalog 或目标表缺少映射字段，或 Catalog 字段类型不受支持："
                        + sourceColumns.get(index) + "，尚未清空目标表");
            }
            String expression = quote(sourceColumns.get(index));
            String nativeType = nativeTypes.get(sourceColumns.get(index).toLowerCase(Locale.ROOT));
            if (dialect == Dialect.STARROCKS && nativeType == null) {
                throw new IllegalArgumentException("无法确定 StarRocks 源字段类型：" + sourceColumns.get(index) + "，尚未清空目标表");
            }
            if (dialect == Dialect.STARROCKS && nativeType != null && (nativeType.startsWith("ARRAY")
                    || nativeType.startsWith("MAP") || nativeType.startsWith("STRUCT") || nativeType.startsWith("HLL")
                    || nativeType.startsWith("BITMAP") || nativeType.startsWith("PERCENTILE") || nativeType.startsWith("VARBINARY"))) {
                throw new IllegalArgumentException("StarRocks 源字段 " + sourceColumns.get(index)
                        + " 的类型 " + nativeType + " 不支持通过 MySQL JDBC Catalog 无损导入，尚未清空目标表");
            }
            if (dialect == Dialect.ORACLE && nativeType != null) {
                boolean timestamp = sourceColumns.get(index).equalsIgnoreCase(timestampColumn);
                if (nativeType.contains("TIME ZONE") || nativeType.contains("TIMESTAMPTZ")) {
                    if (timestamp || !isText(targetType)) {
                        throw new IllegalArgumentException("Oracle 带时区字段 " + sourceColumns.get(index)
                                + " 需要显式时区转换，当前 Catalog 导入不支持写入普通日期时间列，尚未清空目标表");
                    }
                } else if ("DATE".equals(nativeType) || nativeType.startsWith("TIMESTAMP")) {
                    Matcher precision = Pattern.compile("^TIMESTAMP\\((\\d+)\\)").matcher(nativeType);
                    if (precision.find() && Integer.parseInt(precision.group(1)) > 6 && targetType.startsWith("DATETIME")) {
                        throw new IllegalArgumentException("Oracle 时间戳字段 " + sourceColumns.get(index)
                                + " 的精度超过 StarRocks DATETIME 支持的微秒精度，尚未清空目标表");
                    }
                    if ("DATE".equals(sourceType) && (timestamp || !"DATE".equals(targetType))) {
                        throw new IllegalArgumentException("Oracle 字段 " + sourceColumns.get(index)
                                + " 在 Catalog 中映射为 DATE，会丢失时分秒；请使用支持 oracle.temporal.to-datetime 的版本"
                                + "并配置为 true，确认 DESCRIBE 为 DATETIME，尚未清空目标表");
                    }
                    if (isText(sourceType)) {
                        if (timestamp) projection.oracleTextTimestamp = true;
                        if (targetType.startsWith("DATETIME") || "DATE".equals(targetType)) {
                            expression = datetimeProjection(expression);
                        }
                    }
                }
            }
            if ("JSON".equals(targetType) && isText(sourceType)) {
                // JDBC Catalog JSON can be VARCHAR. SR 3.1's implicit CAST changes scalar JSON types.
                expression = StarRocksExternalFullImport.jsonProjection(expression);
                long limit = StarRocksExternalFullImport.MAX_JSON_TEXT_BYTES - 2L;
                Matcher capacity = Pattern.compile("^(?:VAR)?CHAR\\s*\\(\\s*(\\d+)\\s*\\)$").matcher(sourceType);
                if (capacity.matches()) {
                    try { limit = Math.min(limit, Long.parseLong(capacity.group(1))); }
                    catch (NumberFormatException invalid) { throw new IllegalArgumentException("无法确认 Catalog 文本字段容量，尚未清空目标表"); }
                } else if (!"STRING".equals(sourceType)) {
                    // VARCHAR/CHAR without an explicit size use the SR 3.1 default string capacity.
                    limit = Math.min(limit, 65_533L);
                }
                projection.jsonTextIndexes.add(index);
                projection.jsonTextLimits.add(limit);
            } else if ("JSON".equals(targetType) && !"JSON".equals(sourceType)) {
                throw new IllegalArgumentException("目标 JSON 字段只能从 Catalog JSON 或 JSON 文本字段导入，尚未清空目标表");
            }
            expressions.add(expression);
        }
        projection.sql = String.join(", ", expressions);
        return projection;
    }

    private static boolean isText(String type) {
        return "STRING".equals(type) || type.startsWith("VARCHAR") || type.startsWith("CHAR");
    }

    private static final class Projection {
        String sql;
        boolean oracleTextTimestamp;
        final List<Integer> jsonTextIndexes = new ArrayList<>();
        final List<Long> jsonTextLimits = new ArrayList<>();
    }

    private static String datetimeProjection(String raw) {
        String parsed = "CAST(" + raw + " AS DATETIME)";
        return "IF(ASSERT_TRUE(" + raw + " IS NULL OR " + parsed
                + " IS NOT NULL, 'Catalog datetime conversion failed'), " + parsed + ", NULL)";
    }

    private static Map<String, String> nativeTypes(Connection source, String table, List<String> columns,
            Dialect dialect, DataSyncCancellation cancellation) throws SQLException {
        if (dialect == Dialect.STARROCKS) return describe(source, table, cancellation);
        Map<String, String> types = new HashMap<>();
        if (dialect != Dialect.ORACLE) return types;
        List<String> selected = new ArrayList<>();
        for (String column : columns) selected.add(dialect.quote(column));
        stopped(cancellation);
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + String.join(", ", selected) + " FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            for (int index = 0; index < columns.size(); index++) {
                stopped(cancellation);
                String type = metadata.getColumnTypeName(index + 1);
                if (type == null) throw new IllegalArgumentException("无法确定 Oracle 源字段类型，尚未清空目标表");
                if ("TIMESTAMP".equalsIgnoreCase(type)) type += "(" + metadata.getScale(index + 1) + ")";
                types.put(columns.get(index).toLowerCase(Locale.ROOT), type.toUpperCase(Locale.ROOT));
            }
        }
        return types;
    }

    private static boolean verifyTimestamp(Connection source, String sourceTable, String timestamp,
            Dialect dialect) throws SQLException {
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + timestamp + " FROM " + sourceTable + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            int type = metadata.getColumnType(1);
            String name = metadata.getColumnTypeName(1);
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if ((type != Types.DATE && type != Types.TIMESTAMP)
                    || normalized.contains("time zone") || normalized.contains("timestamptz")
                    || normalized.contains("datetimeoffset") || normalized.contains("rowversion")
                    || (dialect == Dialect.SQLSERVER && "timestamp".equals(normalized))) {
                throw new IllegalArgumentException("Catalog 导入的时间戳字段仅支持日期或普通日期时间类型；"
                        + "不支持数值时间戳、带时区类型或 SQL Server timestamp/rowversion，尚未清空目标表");
            }
            return dialect == Dialect.MYSQL && "timestamp".equals(normalized);
        }
    }

    private void preflight(List<String> sourceColumns, List<String> targetColumns) throws Exception {
        stopped(cancellation);
        try (Statement statement = target.createStatement()) {
            statement.execute("SET enable_insert_strict = true");
            stopped(cancellation);
            try (ResultSet rows = statement.executeQuery("SHOW VARIABLES LIKE 'enable_insert_strict'")) {
                if (!rows.next() || !truth(rows.getString(2))) {
                    throw new IllegalStateException("StarRocks 严格导入模式未启用，尚未清空目标表");
                }
            }
            stopped(cancellation);
            long timeout;
            try (ResultSet rows = statement.executeQuery("SHOW VARIABLES LIKE 'query_timeout'")) {
                if (!rows.next()) throw new IllegalStateException("无法确认 StarRocks query_timeout，尚未清空目标表");
                timeout = rows.getLong(2);
            }
            if (timeout < 3600) statement.execute("SET SESSION query_timeout = 3600");
            stopped(cancellation);
            // Force an actual scan on the Catalog path; EXPLAIN alone does not verify BE access.
            String filter = timestampColumn == null ? "" : " WHERE " + quote(timestampColumn) + " IS NOT NULL";
            try (ResultSet rows = statement.executeQuery("SELECT " + selectColumns + " FROM " + catalogTable + filter + " LIMIT 1")) {
                if (rows.next()) {
                    for (int index = 1; index <= sourceColumns.size(); index++) rows.getObject(index);
                } else if (sourceCount > 0) {
                    throw new IllegalStateException("源端统计存在有效数据，但 Catalog 未读取到数据；请检查库表映射、账号和数据可见范围，尚未清空目标表");
                }
            }
            stopped(cancellation);
            // Older Oracle Catalog implementations can push a datetime as a bare string literal,
            // which fails under the source NLS_DATE_FORMAT. Exercise a real window before TRUNCATE.
            if (oracle && start != null) {
                try (ResultSet rows = statement.executeQuery("SELECT " + selectColumns + " FROM " + catalogTable
                        + " WHERE " + windowPredicate(start, unit.next(start)) + " LIMIT 1")) {
                    while (rows.next()) for (int index = 1; index <= sourceColumns.size(); index++) rows.getObject(index);
                } catch (SQLException failure) {
                    throw safe("Oracle Catalog 时间窗口预检失败，请检查目标版本的日期条件下推与源库 NLS 日期格式，尚未清空目标表", failure);
                }
                stopped(cancellation);
            }
            try (ResultSet rows = statement.executeQuery("EXPLAIN " + insertSql("1=0"))) {
                while (rows.next()) stopped(cancellation);
            }
        }
        stopped(cancellation);
    }

    private static Map<String, String> describe(Connection connection, String table, DataSyncCancellation cancellation) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("DESCRIBE " + table)) {
            while (rows.next()) {
                stopped(cancellation);
                String name = rows.getString(1);
                String type = rows.getString(2);
                if (name != null && type != null) columns.put(name.toLowerCase(Locale.ROOT), type.toUpperCase(Locale.ROOT));
            }
        }
        return columns;
    }

    Result execute() throws Exception {
        stopped(cancellation);
        if (closed || executed) throw new IllegalStateException("Catalog 导入已关闭或已执行");
        executed = true;
        if (sourceCount == 0) {
            report(0, "源表有效数据为 0，无需导入");
            return new Result(0, null);
        }
        long processed = 0;
        report(0, "源端有效数据 " + sourceCount + " 条，采用" + (start == null ? "整批" : "按自然" + unit.label)
                + " Catalog 导入" + (timestampColumn == null ? "" : "，跳过时间戳为 NULL 的数据"));
        if (start == null) {
            processed = insert(timestampColumn == null ? null : quote(timestampColumn) + " IS NOT NULL");
            report(processed, "Catalog 整批导入完成");
        } else {
            for (LocalDate current = start; current.isBefore(end); current = unit.next(current)) {
                stopped(cancellation);
                LocalDate next = unit.next(current);
                processed = Math.addExact(processed, insert(windowPredicate(current, next)));
                report(processed, "Catalog 按" + unit.label + "导入 [" + current + ", " + next + ")");
            }
        }
        stopped(cancellation);
        return new Result(processed, maxTimestamp);
    }

    private String windowPredicate(LocalDate current, LocalDate next) {
        String ts = quote(timestampColumn);
        return ts + " >= '" + current + " 00:00:00' AND " + ts + " < '" + next + " 00:00:00'";
    }

    private String insertSql(String predicate) {
        return "INSERT INTO " + targetTable + " (" + insertColumns + ") SELECT " + selectColumns
                + " FROM " + catalogTable + (predicate == null ? "" : " WHERE " + predicate);
    }

    private long insert(String predicate) throws Exception {
        stopped(cancellation);
        try (Statement statement = target.createStatement()) {
            long affected = statement.executeLargeUpdate(insertSql(predicate));
            stopped(cancellation);
            if (affected < 0) throw new IllegalStateException("StarRocks 未返回有效导入行数，同步进度未推进");
            if (!target.getAutoCommit()) target.commit();
            stopped(cancellation);
            return affected;
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("Catalog INSERT SELECT 失败，同步进度未推进", failure);
        }
    }

    private void report(long processed, String message) throws SQLException {
        stopped(cancellation);
        if (callback != null) callback.onProgress((int) Math.min(processed, Integer.MAX_VALUE), -1, message + " (" + processed + ")");
        stopped(cancellation);
    }

    @Override public void close() {
        // The user owns this Catalog, including its metadata and cached driver. Never drop it.
        closed = true;
    }

    private static Map<String, String> verifyCatalog(Connection target, Connection source, ErDatasource datasource,
            Dialect dialect, String catalog, String schema, DataSyncCancellation cancellation) throws Exception {
        String ddl;
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery("SHOW CREATE CATALOG " + quote(catalog))) {
            if (!rows.next()) throw new IllegalArgumentException("指定的 Catalog 不存在或当前账号无权限，尚未清空目标表");
            ddl = rows.getString(2);
        }
        stopped(cancellation);
        Map<String, String> properties = new HashMap<>();
        Matcher matcher = PROPERTY.matcher(ddl == null ? "" : ddl);
        while (matcher.find()) properties.put(matcher.group(1).toLowerCase(Locale.ROOT), unescape(matcher.group(2)));
        if (!"jdbc".equalsIgnoreCase(properties.get("type"))) {
            throw new IllegalArgumentException("指定的 Catalog 必须为 JDBC Catalog，尚未清空目标表");
        }
        // Source statistics and Catalog scans may use independent credentials. The configured
        // accounts must each have access to the intended tables and rows; verify readability below.
        JdbcIdentity configured = JdbcIdentity.parse(datasource.getJdbcUrl(), dialect);
        JdbcIdentity external = JdbcIdentity.parse(properties.get("jdbc_uri"), dialect);
        // These URLs belong to different network environments. Host names and ports can differ
        // because of NAT, proxies, or DNS aliases; the user-provided Catalog owns its source route.
        String actualUrl = source.getMetaData().getURL();
        if (actualUrl != null && !actualUrl.trim().isEmpty()) {
            configured.requireSameSourceEndpoint(JdbcIdentity.parse(actualUrl, dialect));
        }
        if (dialect == Dialect.POSTGRESQL || dialect == Dialect.SQLSERVER) {
            String database = dialect == Dialect.SQLSERVER ? first(schema, source.getCatalog(), datasource.getDatabaseName())
                    : first(source.getCatalog(), datasource.getDatabaseName(), configured.database);
            if (database == null || external.database == null || !database.equals(external.database)) {
                throw new IllegalArgumentException("Catalog 绑定的数据库与当前源数据库不一致；PG/SQL Server 的 Catalog 固定连接单个数据库，尚未清空目标表");
            }
        }
        if (dialect == Dialect.ORACLE && !equal(configured.database, external.database)) {
            throw new IllegalArgumentException("Catalog 绑定的 Oracle 服务或 SID 与源连接不一致，尚未清空目标表");
        }
        StarRocksCatalogSessionSettings.verify(source, datasource, properties, cancellation);
        return properties;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
    }

    private static void verifyDistinctStarRocksTable(ErDatasource datasource, Connection source, Connection target,
            Map<String, String> properties, String scope, String table, String targetTable,
            DataSyncCancellation cancellation) throws SQLException {
        Matcher names = TARGET_PARTS.matcher(targetTable);
        if (!names.matches() || !scope.equals(names.group(1).replace("``", "`"))
                || !table.equals(names.group(2).replace("``", "`"))) return;
        java.sql.DatabaseMetaData metadata = target.getMetaData();
        String url = metadata == null ? null : metadata.getURL();
        JdbcIdentity destination = null;
        if (url != null) {
            try { destination = JdbcIdentity.parse(url, Dialect.STARROCKS); }
            catch (IllegalArgumentException unsupportedTargetUrl) { /* Compare actual cluster IDs below. */ }
        }
        boolean sameEndpoint = destination != null && (destination.sameEndpoint(JdbcIdentity.parse(datasource.getJdbcUrl(), Dialect.STARROCKS))
                || destination.sameEndpoint(JdbcIdentity.parse(properties.get("jdbc_uri"), Dialect.STARROCKS)));
        // Different FE nodes, DNS aliases and public/private addresses may all serve the same cluster.
        // Check identity only when the database and table names coincide; other mappings need no extra privilege.
        if (sameEndpoint || clusterId(source, cancellation) == clusterId(target, cancellation)) {
            throw new IllegalArgumentException("StarRocks 源表与目标表为同一表，初始化清空会删除源数据，请更换目标表，尚未清空目标表");
        }
    }

    private static long clusterId(Connection connection, DataSyncCancellation cancellation) throws SQLException {
        stopped(cancellation);
        Long clusterId = null;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SHOW FRONTENDS")) {
            while (rows.next()) {
                stopped(cancellation);
                long value = rows.getLong("ClusterId");
                if (rows.wasNull() || value <= 0 || (clusterId != null && clusterId != value)) {
                    throw new IllegalArgumentException("无法确认 StarRocks 集群身份，不能初始化到同名库表，尚未清空目标表");
                }
                clusterId = value;
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("源和目标 StarRocks 库表同名，需要两端账号具有读取 SHOW FRONTENDS 的权限以核对集群身份；"
                    + "也可改用不同目标库或表名，尚未清空目标表", failure);
        }
        if (clusterId == null) throw new IllegalArgumentException("无法读取 StarRocks 集群身份，不能初始化到同名库表，尚未清空目标表");
        return clusterId;
    }

    private static boolean equal(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static boolean truth(String value) { return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value); }
    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return null;
    }

    private static LocalDate sourceDay(String value) {
        try {
            if (value == null || value.length() < 10) throw new IllegalArgumentException();
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("源时间戳日期格式无效，尚未清空目标表");
        }
    }

    private static void validateColumns(List<String> source, List<String> target) {
        if (source == null || target == null || source.isEmpty() || source.size() != target.size()) {
            throw new IllegalArgumentException("源与目标字段映射无效");
        }
        for (List<String> columns : java.util.Arrays.asList(source, target)) {
            Set<String> seen = new HashSet<>();
            for (String column : columns) {
                quote(column);
                if (!seen.add(column.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("字段映射包含重复字段");
            }
        }
    }

    private static String join(List<String> columns) {
        List<String> quoted = new ArrayList<>();
        for (String column : columns) quoted.add(quote(column));
        return String.join(", ", quoted);
    }

    static String quote(String identifier) {
        if (identifier == null || identifier.isEmpty() || identifier.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("库、表或字段名称无效");
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static void stopped(DataSyncCancellation cancellation) throws DataSyncCancellation.SyncStoppedException {
        if (cancellation != null) cancellation.checkStopped();
    }

    private static SQLException safe(String operation, SQLException failure) {
        if (failure instanceof DataSyncCancellation.SyncStoppedException) return failure;
        // Neither SHOW CREATE's JDBC properties nor driver messages may leak credentials to task logs.
        return new CatalogSQLException(operation, failure);
    }

    private static final class CatalogSQLException extends SQLException {
        CatalogSQLException(String operation, SQLException failure) {
            super(operation + "（SQLState=" + failure.getSQLState() + ", errorCode=" + failure.getErrorCode()
                    + "）", failure.getSQLState(), failure.getErrorCode());
        }
    }

    enum Dialect {
        MYSQL, POSTGRESQL, ORACLE, SQLSERVER, STARROCKS;
        static Dialect of(String type) {
            if ("TIDB".equalsIgnoreCase(type) || "MYSQL".equalsIgnoreCase(type)) return MYSQL;
            for (Dialect dialect : values()) if (dialect.name().equalsIgnoreCase(type)) return dialect;
            throw new IllegalArgumentException("Catalog 导入目前支持 MySQL、TiDB、PostgreSQL、Oracle、SQL Server、StarRocks 源");
        }
        String quote(String value) {
            StarRocksCatalogFullImport.quote(value);
            if (this == MYSQL || this == STARROCKS) return StarRocksCatalogFullImport.quote(value);
            if (this == SQLSERVER) return "[" + value.replace("]", "]]") + "]";
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        String utf8Length(String expression) {
            if (this == MYSQL) return "OCTET_LENGTH(CAST(" + expression + " AS CHAR CHARACTER SET utf8mb4))";
            if (this == STARROCKS) return "LENGTH(CAST(" + expression + " AS VARCHAR))";
            if (this == POSTGRESQL) return "OCTET_LENGTH(CONVERT_TO(CAST(" + expression + " AS text), 'UTF8'))";
            if (this == SQLSERVER) return "CAST(DATALENGTH(CONVERT(NVARCHAR(MAX), " + expression + ")) AS BIGINT) * 2";
            return "LENGTH(" + expression + ") * 4";
        }
        String scope(Connection source, ErDatasource datasource, String schema) throws SQLException {
            if (this == SQLSERVER) return "dbo";
            if (this == POSTGRESQL) return first(schema, "public");
            if (this == ORACLE) return first(schema, datasource.getUsername(), source.getMetaData().getUserName()).toUpperCase(Locale.ROOT);
            String scope = first(schema, source.getCatalog(), datasource.getDatabaseName());
            if (scope == null) throw new IllegalArgumentException("无法确定源数据库名称");
            return scope;
        }
        String sourceTable(Connection source, ErDatasource datasource, String schema, String scope, String table) throws SQLException {
            if (this == SQLSERVER) {
                String database = first(schema, source.getCatalog(), datasource.getDatabaseName());
                if (database == null) throw new IllegalArgumentException("无法确定 SQL Server 源数据库");
                return quote(database) + "." + quote("dbo") + "." + quote(table);
            }
            return quote(scope) + "." + quote(table);
        }
    }

    private static final class JdbcIdentity {
        final String host;
        final int port;
        final String database;
        JdbcIdentity(String host, int port, String database) { this.host = host; this.port = port; this.database = database; }
        boolean sameEndpoint(JdbcIdentity other) { return host.equalsIgnoreCase(other.host) && port == other.port; }
        void requireSameSourceEndpoint(JdbcIdentity other) {
            if (!sameEndpoint(other)) {
                throw new IllegalArgumentException("实际源 JDBC 连接与源数据源配置的服务器地址或端口不一致，尚未清空目标表");
            }
        }
        static JdbcIdentity parse(String url, Dialect dialect) {
            try {
                if (url == null) throw new IllegalArgumentException();
                if (dialect == Dialect.ORACLE) {
                    Matcher oracle = Pattern.compile("^jdbc:oracle:thin:@(?://)?(\\[[^]]+\\]|[^:/;?]+):(\\d+)([:/])([^?;]+)(?:[?;].*)?$", Pattern.CASE_INSENSITIVE).matcher(url);
                    if (!oracle.matches()) throw new IllegalArgumentException();
                    return new JdbcIdentity(oracle.group(1), Integer.parseInt(oracle.group(2)), oracle.group(3) + oracle.group(4));
                }
                String prefix = dialect == Dialect.MYSQL || dialect == Dialect.STARROCKS ? "jdbc:mysql:"
                        : dialect == Dialect.POSTGRESQL ? "jdbc:postgresql:" : "jdbc:sqlserver:";
                if (!url.regionMatches(true, 0, prefix, 0, prefix.length())) throw new IllegalArgumentException();
                String remainder = url.substring(prefix.length());
                String database = null;
                if (dialect == Dialect.SQLSERVER) {
                    String[] chunks = remainder.split(";", -1);
                    remainder = chunks[0];
                    for (int index = 1; index < chunks.length; index++) {
                        String[] property = chunks[index].split("=", 2);
                        if (property.length == 2 && "databaseName".equalsIgnoreCase(property[0].trim())) database = property[1].trim();
                    }
                }
                URI uri = URI.create("jdbcsource:" + remainder);
                if (uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
                int port = uri.getPort();
                // The MySQL JDBC driver defaults to 3306 even when its server is StarRocks.
                // A normal StarRocks FE therefore needs :9030 explicitly in jdbc:mysql URLs.
                if (port < 0) port = dialect == Dialect.MYSQL || dialect == Dialect.STARROCKS ? 3306
                        : dialect == Dialect.POSTGRESQL ? 5432 : 1433;
                if (port < 1 || port > 65535) throw new IllegalArgumentException();
                if (dialect != Dialect.SQLSERVER && uri.getRawPath() != null && uri.getRawPath().length() > 1) {
                    database = URLDecoder.decode(uri.getRawPath().substring(1), "UTF-8");
                }
                return new JdbcIdentity(uri.getHost(), port, database);
            } catch (Exception invalid) {
                throw new IllegalArgumentException("无法安全核对 Catalog 的 JDBC 连接；本版支持标准单节点 JDBC 地址，尚未清空目标表");
            }
        }
    }

    private enum WindowUnit {
        YEAR("年"), MONTH("月"), DAY("日");
        final String label;
        WindowUnit(String label) { this.label = label; }
        static WindowUnit of(String value) {
            if (value == null) return YEAR;
            try { return valueOf(value); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Catalog 导入时间单位仅支持 YEAR、MONTH 或 DAY"); }
        }
        LocalDate start(LocalDate date) { return this == YEAR ? date.withDayOfYear(1) : this == MONTH ? date.withDayOfMonth(1) : date; }
        LocalDate next(LocalDate date) { return this == YEAR ? date.plusYears(1) : this == MONTH ? date.plusMonths(1) : date.plusDays(1); }
    }

    static final class Result {
        final long processed;
        final Timestamp maxTs;
        Result(long processed, Timestamp maxTs) { this.processed = processed; this.maxTs = maxTs; }
    }
}
