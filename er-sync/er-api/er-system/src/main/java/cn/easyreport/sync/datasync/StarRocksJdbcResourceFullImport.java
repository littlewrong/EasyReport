package cn.easyreport.sync.datasync;

import cn.easyreport.sync.core.SyncProgressCallback;
import cn.easyreport.sync.domain.ErDatasource;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns a per-table JDBC external table, but never changes the user's shared JDBC Resource. */
final class StarRocksJdbcResourceFullImport implements AutoCloseable {
    private static final String IDENTIFIER = "(?:`(?:``|[^`])+`|[a-zA-Z0-9_$\\u0080-\\uFFFF]+)";
    private static final Pattern TARGET = Pattern.compile("^" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")?$");
    private static final Pattern TEXT_CAPACITY = Pattern.compile("^(?:VAR)?CHAR\\s*\\(\\s*([0-9]+)\\s*\\)$");
    private static final Pattern BINARY_CAPACITY = Pattern.compile("^(?:VAR)?BINARY(?:\\s*\\(\\s*([0-9]+)\\s*\\))?$");
    private static final long DEFAULT_TEXT_CAPACITY = 65_533;
    private static final long MAX_BINARY_CAPACITY = 1_048_576;
    static final String BINARY_CONVERSION_PROBE_SQL = "SELECT HEX(TO_BINARY('00FF', 'hex')), "
            + "LENGTH(HEX(TO_BINARY('', 'hex'))), TO_BINARY(NULL, 'hex') IS NULL";

    private final Connection target;
    private final String resourceName;
    private final Map<String, String> resourceProperties;
    private final String externalTable;
    private final String targetTable;
    private final String targetColumns;
    private final String selectColumns;
    private final List<String> selectExpressions;
    private final boolean jsonProjection;
    private final Map<Integer, Long> targetTextCapacities;
    private final Map<Integer, Long> targetBinaryCapacities;
    private final List<Integer> targetJsonColumns;
    private final List<IntegerNarrowing> integerNarrowings;
    private final StarRocksJdbcResourceSource.Plan source;
    private final LocalDate firstWindow;
    private final LocalDate endExclusive;
    private final WindowUnit unit;
    private final SyncProgressCallback callback;
    private final DataSyncCancellation cancellation;
    private boolean createAttempted;
    private boolean executed;
    private boolean closed;

    private StarRocksJdbcResourceFullImport(Connection target, String resourceName,
            Map<String, String> resourceProperties, String targetTable, List<String> targetColumns,
            TargetProjection projection,
            StarRocksJdbcResourceSource.Plan source, LocalDate firstWindow, LocalDate endExclusive,
            WindowUnit unit, Long taskId, SyncProgressCallback callback, DataSyncCancellation cancellation) {
        this.target = target;
        this.resourceName = resourceName;
        this.resourceProperties = new HashMap<>(resourceProperties);
        this.targetTable = targetTable;
        this.targetColumns = joinColumns(targetColumns);
        this.targetTextCapacities = new LinkedHashMap<>(projection.textCapacities);
        this.targetBinaryCapacities = new LinkedHashMap<>(projection.binaryCapacities);
        this.targetJsonColumns = new ArrayList<>(projection.jsonColumns);
        this.integerNarrowings = new ArrayList<>(projection.integerNarrowings);
        this.selectColumns = projection.sql;
        this.selectExpressions = new ArrayList<>(projection.expressions);
        this.jsonProjection = projection.json;
        this.source = source;
        this.firstWindow = firstWindow;
        this.endExclusive = endExclusive;
        this.unit = unit;
        this.callback = callback;
        this.cancellation = cancellation;
        this.externalTable = quote(StarRocksExternalFullImport.IMPORT_DATABASE) + "." + quote("er_jdbc_import_"
                + (taskId == null ? "0" : Long.toUnsignedString(taskId)) + "_"
                + UUID.randomUUID().toString().replace("-", ""));
    }

    static StarRocksJdbcResourceFullImport prepare(ErDatasource datasource, Connection sourceConnection,
            Connection target, String resourceName, String schema, String table, String targetTable,
            List<String> sourceColumns, List<String> targetColumns, String timestampField,
            Long taskId, Integer thresholdWan, String windowUnit, SyncProgressCallback callback,
            DataSyncCancellation cancellation) throws Exception {
        stopped(cancellation);
        if (resourceName == null || !resourceName.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("请填写有效的已有 JDBC Resource 名称（字母开头，最多 64 个字母、数字或下划线），尚未清空目标表");
        }
        if (targetTable == null || !TARGET.matcher(targetTable).matches()
                || targetTable.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("JDBC Resource 导入目标表名格式无效，尚未清空目标表");
        }
        validateColumns(sourceColumns, targetColumns);
        int threshold = thresholdWan == null ? StarRocksExternalFullImport.DEFAULT_THRESHOLD_WAN : thresholdWan;
        if (threshold < 1) throw new IllegalArgumentException("JDBC Resource 整批导入阈值必须为正整数，单位为万条");
        WindowUnit unit = WindowUnit.of(windowUnit);
        Map<String, String> properties = readResource(target, resourceName, cancellation);
        StarRocksJdbcResourceSource.Plan source = StarRocksJdbcResourceSource.prepare(datasource,
                sourceConnection, schema, table, sourceColumns, timestampField, properties, cancellation);
        stopped(cancellation);
        TargetProjection projection = validateTargetColumns(target, targetTable, targetColumns, source, cancellation);

        LocalDate firstWindow = null;
        LocalDate endExclusive = null;
        if (source.timestampColumn != null && source.sourceRowCount > 0) {
            if (source.minTs == null || source.maxTs == null || source.minDay == null || source.maxDay == null
                    || source.minTs.after(source.maxTs) || source.minDay.isAfter(source.maxDay)) {
                throw new IllegalArgumentException("JDBC Resource 源时间戳范围无效，尚未清空目标表");
            }
            if (source.sourceRowCount >= threshold * 10_000L) {
                firstWindow = unit.start(source.minDay);
                endExclusive = unit.next(unit.start(source.maxDay));
                if (firstWindow.getYear() < 1 || endExclusive.getYear() > 9999) {
                    throw new IllegalArgumentException("源时间戳无法构造安全的自然时间窗口，尚未清空目标表");
                }
            }
        }

        StarRocksJdbcResourceFullImport importer = new StarRocksJdbcResourceFullImport(target, resourceName,
                properties, targetTable, targetColumns, projection, source, firstWindow, endExclusive, unit,
                taskId, callback, cancellation);
        try {
            importer.prepareTargetSession();
            if (importer.jsonProjection) StarRocksExternalFullImport.verifyJsonProjection(target, cancellation);
            importer.verifyBinaryDecoderSemantics();
            stopped(cancellation);
            try (Statement statement = target.createStatement()) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + quote(StarRocksExternalFullImport.IMPORT_DATABASE));
                stopped(cancellation);
                String ddl = "CREATE EXTERNAL TABLE " + importer.externalTable + " (" + source.externalColumnsSql
                        + ") ENGINE=JDBC PROPERTIES (\"resource\"=" + property(resourceName)
                        + ",\"table\"=" + property(source.sourceTableProperty) + ")";
                importer.createAttempted = true;
                statement.execute(ddl);
            }
            importer.preflight();
            importer.verifyResourceUnchanged();
            return importer;
        } catch (Exception failure) {
            Exception primary = failure;
            try {
                stopped(cancellation);
            } catch (DataSyncCancellation.SyncStoppedException stopped) {
                // Drivers may report a generic SQLException after cancel(). Classify only from
                // our stop context, and never attach the original driver diagnostics/credentials.
                primary = stopped;
            }
            try {
                importer.close();
            } catch (Exception cleanup) {
                primary.addSuppressed(cleanup);
            }
            if (primary instanceof SQLException && !(primary instanceof ResourceSQLException)
                    && !(primary instanceof DataSyncCancellation.SyncStoppedException)) {
                SQLException safe = safe("JDBC Resource 外表创建或实际读取预检失败；请检查 Resource、驱动、BE 网络及源 SQL 兼容性，尚未清空目标表",
                        (SQLException) primary);
                for (Throwable suppressed : primary.getSuppressed()) safe.addSuppressed(suppressed);
                throw safe;
            }
            throw primary;
        }
    }

    private void prepareTargetSession() throws SQLException {
        stopped(cancellation);
        try (Statement statement = target.createStatement()) {
            statement.execute("SET enable_insert_strict = true");
            stopped(cancellation);
            requireTrue(statement, "enable_insert_strict");
            statement.execute("SET enable_pipeline_engine = true");
            stopped(cancellation);
            requireTrue(statement, "enable_pipeline_engine");
            try (ResultSet rows = statement.executeQuery("SHOW VARIABLES LIKE 'query_timeout'")) {
                if (!rows.next()) throw new IllegalStateException("无法确认 JDBC Resource 导入会话超时，尚未清空目标表");
                long timeout = rows.getLong(2);
                if (rows.wasNull() || timeout < 0) throw new IllegalStateException("JDBC Resource 导入会话超时无效，尚未清空目标表");
                if (timeout < 3600) {
                    stopped(cancellation);
                    statement.execute("SET SESSION query_timeout = 3600");
                }
            }
        }
        stopped(cancellation);
    }

    private void requireTrue(Statement statement, String variable) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SHOW VARIABLES LIKE '" + variable + "'")) {
            if (!rows.next() || !("true".equalsIgnoreCase(rows.getString(2)) || "1".equals(rows.getString(2)))) {
                throw new IllegalStateException("JDBC Resource 导入要求启用 " + variable + "，尚未清空目标表");
            }
        }
        stopped(cancellation);
    }

    private void preflight() throws SQLException {
        // SR 3.1.5's BE rebuilds SQL from raw Resource table/column names. EXPLAIN alone
        // cannot validate driver access, name folding, result Java classes or pushed date filters.
        validateActualIntegerRanges();
        probe(source.timestampColumn == null ? null : quote(source.timestampColumn) + " IS NOT NULL", true);
        validateActualTextCapacity();
        verifyBinaryTargetValues();
        verifyKingbaseJsonValues();
        verifyTimestampWallClocks();
        if (firstWindow != null) {
            for (LocalDate start = firstWindow; start.isBefore(endExclusive); start = unit.next(start)) {
                stopped(cancellation);
                probe(windowPredicate(start, unit.next(start)), false);
            }
        }
        stopped(cancellation);
        try (Statement statement = target.createStatement();
                ResultSet rows = statement.executeQuery("EXPLAIN " + insertSql("1=0"))) {
            while (rows.next()) stopped(cancellation);
        }
        stopped(cancellation);
    }

    private void verifyBinaryDecoderSemantics() throws SQLException {
        if (targetBinaryCapacities.isEmpty()) return;
        stopped(cancellation);
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(BINARY_CONVERSION_PROBE_SQL)) {
            if (!rows.next()) throw new IllegalStateException("StarRocks 二进制解码预检未返回结果，尚未清空目标表");
            String decodedHex = rows.getString(1);
            long emptyBytes = rows.getLong(2);
            boolean emptyIsNull = rows.wasNull();
            boolean preservesNull = rows.getBoolean(3);
            if (!"00FF".equals(decodedHex) || emptyIsNull || emptyBytes != 0 || !preservesNull || rows.wasNull()) {
                throw new IllegalStateException("StarRocks TO_BINARY 未保留 HEX、空二进制或 NULL 语义，尚未清空目标表");
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("StarRocks 二进制 HEX 解码预检失败，尚未清空目标表", failure);
        }
        stopped(cancellation);
    }

    private void verifyBinaryTargetValues() throws SQLException {
        if (targetBinaryCapacities.isEmpty()) return;
        List<String> aggregates = new ArrayList<>();
        for (int index : targetBinaryCapacities.keySet()) {
            aggregates.add("MAX(LENGTH(HEX(" + selectExpressions.get(index) + ")))");
        }
        String filter = source.timestampColumn == null ? "" : " WHERE " + quote(source.timestampColumn) + " IS NOT NULL";
        stopped(cancellation);
        // LIMIT 1 cannot prove later rows contain valid HEX. Evaluate the same guarded
        // decoder over every eligible value before clearing the destination.
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + String.join(", ", aggregates) + " FROM " + externalTable + filter)) {
            if (!rows.next()) throw new IllegalStateException("JDBC Resource 二进制实际读取预检未返回结果，尚未清空目标表");
            int resultIndex = 1;
            for (Map.Entry<Integer, Long> entry : targetBinaryCapacities.entrySet()) {
                stopped(cancellation);
                long encodedBytes = rows.getLong(resultIndex++);
                if (!rows.wasNull() && (encodedBytes < 0 || encodedBytes > entry.getValue() * 2)) {
                    throw new IllegalArgumentException("JDBC Resource 源二进制字段 " + source.columns.get(entry.getKey()).name
                            + " 解码后超出目标字段容量（" + entry.getValue() + " 字节），尚未清空目标表");
                }
            }
        }
        stopped(cancellation);
    }

    private void verifyKingbaseJsonValues() throws SQLException {
        if (!"KINGBASE".equals(source.sourceType) || targetJsonColumns.isEmpty()) return;
        String filter = source.timestampColumn == null ? "" : " WHERE " + quote(source.timestampColumn) + " IS NOT NULL";
        for (int index : targetJsonColumns) {
            stopped(cancellation);
            StarRocksJdbcResourceSource.Column column = source.columns.get(index);
            String scanColumn = quote(column.scanName);
            String sql = "SELECT " + scanColumn + ", JSON_STRING("
                    + StarRocksExternalFullImport.jsonProjection(scanColumn) + ") FROM " + externalTable + filter;
            try (Statement statement = target.createStatement()) {
                // Connector/J's streaming mode keeps a full-column verification from
                // buffering all JSON documents in the application before the first row.
                statement.setFetchSize(Integer.MIN_VALUE);
                stopped(cancellation);
                try (ResultSet rows = statement.executeQuery(sql)) {
                    while (rows.next()) {
                        stopped(cancellation);
                        String original = rows.getString(1);
                        String normalized = rows.getString(2);
                        stopped(cancellation);
                        StarRocksJdbcResourceJsonValues.requireEquivalent(original, normalized, column.name);
                    }
                }
            } catch (SQLException failure) {
                stopped(cancellation);
                throw safe("金仓 JSON 字段 " + column.name
                        + " 的逐行语义预检读取失败，请改用目标文本列以保留原值，尚未清空目标表", failure);
            }
        }
        stopped(cancellation);
    }

    private void validateActualIntegerRanges() throws SQLException {
        if (integerNarrowings.isEmpty()) return;
        List<String> aggregates = new ArrayList<>();
        for (IntegerNarrowing narrowing : integerNarrowings) {
            aggregates.add("COUNT(" + narrowing.column + ")");
            aggregates.add("COUNT(" + narrowing.expression + ")");
            aggregates.add("MIN(" + narrowing.expression + ")");
            aggregates.add("MAX(" + narrowing.expression + ")");
        }
        String filter = source.timestampColumn == null ? "" : " WHERE " + quote(source.timestampColumn) + " IS NOT NULL";
        stopped(cancellation);
        // Scan every eligible Resource row, including SQLServer, without a pushed LIMIT.
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + String.join(", ", aggregates) + " FROM " + externalTable + filter)) {
            if (!rows.next()) throw new IllegalStateException("JDBC Resource 整数范围预检未返回结果，尚未清空目标表");
            int index = 1;
            for (IntegerNarrowing narrowing : integerNarrowings) {
                stopped(cancellation);
                long values = rows.getLong(index++);
                boolean invalidValues = rows.wasNull() || values < 0;
                long converted = rows.getLong(index++);
                if (invalidValues || rows.wasNull() || converted != values) {
                    throw new IllegalArgumentException("JDBC Resource 字段 " + narrowing.range.columnName
                            + " 存在无法无损读取的整数值，尚未清空目标表");
                }
                BigInteger minimum = narrowing.readInteger(rows.getString(index++));
                BigInteger maximum = narrowing.readInteger(rows.getString(index++));
                if (values == 0 && minimum == null && maximum == null) continue;
                narrowing.range.verify(minimum, maximum);
            }
        }
        stopped(cancellation);
    }

    private void verifyTimestampWallClocks() throws SQLException {
        if (source.sourceRowCount == 0) return;
        String filter = source.timestampColumn == null ? "" : " WHERE " + quote(source.timestampColumn) + " IS NOT NULL";
        for (StarRocksJdbcResourceSource.Column column : source.columns) {
            if (!column.mysqlTimestamp) continue;
            stopped(cancellation);
            String name = quote(column.scanName);
            try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(
                    "SELECT MIN(" + name + "), MAX(" + name + ") FROM " + externalTable + filter)) {
                if (!rows.next()) throw new IllegalStateException("JDBC Resource TIMESTAMP 墙钟预检未返回结果，尚未清空目标表");
                String min = rows.getString(1);
                String max = rows.getString(2);
                if (!sameWallClock(column.mysqlTimestampMinText, min) || !sameWallClock(column.mysqlTimestampMaxText, max)) {
                    throw new IllegalArgumentException("MySQL/TiDB TIMESTAMP 字段 " + column.name
                            + " 的源与 JDBC Resource 墙钟时间不一致，请核对两端 JDBC 会话及 BE JVM 时区，尚未清空目标表");
                }
            }
            stopped(cancellation);
        }
    }

    private static boolean sameWallClock(String source, String target) {
        if (source == null || target == null) return source == null && target == null;
        try {
            return LocalDateTime.parse(source.trim().replace(' ', 'T')).equals(
                    LocalDateTime.parse(target.trim().replace(' ', 'T')));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("无法确认 JDBC Resource TIMESTAMP 墙钟时间格式，尚未清空目标表");
        }
    }

    private void validateActualTextCapacity() throws SQLException {
        if (targetTextCapacities.isEmpty()) return;
        stopped(cancellation);
        List<String> lengths = new ArrayList<>();
        for (int index : targetTextCapacities.keySet()) {
            lengths.add("MAX(LENGTH(" + quote(source.columns.get(index).scanName) + "))");
        }
        String filter = source.timestampColumn == null ? "" : " WHERE " + quote(source.timestampColumn) + " IS NOT NULL";
        // Source LENGTH*4 is only a conservative bound. In particular, ASCII VARCHAR(36)
        // must not be rejected as 144 bytes when its real UTF-8 value occupies 36 bytes.
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + String.join(", ", lengths) + " FROM " + externalTable + filter)) {
            if (!rows.next()) throw new IllegalStateException("JDBC Resource 文本实际字节数预检未返回结果，尚未清空目标表");
            int resultIndex = 1;
            for (Map.Entry<Integer, Long> entry : targetTextCapacities.entrySet()) {
                stopped(cancellation);
                long bytes = rows.getLong(resultIndex++);
                if (rows.wasNull()) bytes = 0;
                StarRocksJdbcResourceSource.Column column = source.columns.get(entry.getKey());
                if (bytes < 0) throw new IllegalStateException("JDBC Resource 文本实际字节数无效，尚未清空目标表");
                if (bytes > column.externalTextCapacity) throw new IllegalArgumentException("JDBC Resource 源文本字段 "
                        + column.name + " 的实际 UTF-8 字节数超出外表通道容量，尚未清空目标表");
                if (bytes > entry.getValue()) {
                    if (targetBinaryCapacities.containsKey(entry.getKey())) {
                        throw new IllegalArgumentException("JDBC Resource 源二进制字段 " + column.name
                                + " 解码后超出目标字段容量（" + targetBinaryCapacities.get(entry.getKey()) + " 字节），尚未清空目标表");
                    }
                    throw new IllegalArgumentException("JDBC Resource 源文本字段 " + column.name
                            + " 的实际 UTF-8 字节数超出目标字段容量（" + entry.getValue() + " 字节），尚未清空目标表");
                }
            }
        }
        stopped(cancellation);
    }

    private void probe(String predicate, boolean requireVisibleData) throws SQLException {
        stopped(cancellation);
        if ("SQLSERVER".equals(source.sourceType)) {
            probeSqlServer(predicate, requireVisibleData);
            return;
        }
        String sql = "SELECT " + selectColumns + " FROM " + externalTable
                + (predicate == null ? "" : " WHERE " + predicate) + " LIMIT 1";
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (rows.next()) {
                for (int index = 1; index <= source.columns.size(); index++) rows.getObject(index);
            } else if (requireVisibleData && source.sourceRowCount > 0) {
                throw new IllegalArgumentException("源端统计存在有效数据，但 JDBC Resource 未读取到数据；请检查库表绑定和账号可见范围，尚未清空目标表");
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            if ("ORACLE".equals(source.sourceType) && predicate != null && predicate.contains(" >= '")) {
                throw safe("Oracle JDBC Resource 时间窗口实际读取预检失败；请检查源库 NLS 日期格式及目标版本日期条件下推兼容性，尚未清空目标表", failure);
            }
            throw failure;
        }
        stopped(cancellation);
    }

    private void probeSqlServer(String predicate, boolean requireVisibleData) throws SQLException {
        // SR 3.1.5's BE appends LIMIT for non-Oracle JDBC URLs. A SQLServer scan must therefore
        // have no planner limit. Aggregating every effective projection both forces the real
        // driver read/type conversion and returns one result row without setMaxRows side effects.
        List<String> projections = new ArrayList<>();
        projections.add("COUNT(*)");
        for (String expression : selectExpressions) {
            projections.add("MAX(LENGTH(CAST(" + expression + " AS VARCHAR("
                    + StarRocksExternalFullImport.MAX_JSON_TEXT_BYTES + "))))");
        }
        String sql = "SELECT " + String.join(", ", projections) + " FROM " + externalTable
                + (predicate == null ? "" : " WHERE " + predicate);
        stopped(cancellation);
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new IllegalStateException("SQL Server JDBC Resource 聚合预检未返回结果，尚未清空目标表");
            long visible = rows.getLong(1);
            if (rows.wasNull() || visible < 0) throw new IllegalStateException("SQL Server JDBC Resource 聚合预检行数无效，尚未清空目标表");
            for (int index = 0; index < source.columns.size(); index++) rows.getObject(index + 2);
            if (requireVisibleData && source.sourceRowCount > 0 && visible == 0) {
                throw new IllegalArgumentException("源端统计存在有效数据，但 SQL Server JDBC Resource 未读取到数据，尚未清空目标表");
            }
        }
        stopped(cancellation);
    }

    Result execute() throws Exception {
        stopped(cancellation);
        if (closed || executed) throw new IllegalStateException("JDBC Resource 导入外表已关闭或已执行");
        executed = true;
        verifyResourceUnchanged();
        String message = "JDBC Resource 导入，源有效行数 " + source.sourceRowCount
                + (source.timestampColumn == null ? "" : "，跳过时间戳为 NULL 的数据");
        report(0, message + (firstWindow == null ? "，整批导入" : "，按自然" + unit.label + "导入"));
        if (source.timestampColumn != null && source.sourceRowCount == 0) return new Result(0, null);
        long processed = 0;
        if (firstWindow == null) {
            processed = insert(source.timestampColumn == null ? null : quote(source.timestampColumn) + " IS NOT NULL");
            report(processed, message + "，整批导入");
        } else {
            for (LocalDate start = firstWindow; start.isBefore(endExclusive); start = unit.next(start)) {
                stopped(cancellation);
                LocalDate end = unit.next(start);
                processed = Math.addExact(processed, insert(windowPredicate(start, end)));
                report(processed, message + "，[" + start + ", " + end + ")");
            }
        }
        stopped(cancellation);
        return new Result(processed, source.maxTs);
    }

    private long insert(String predicate) throws Exception {
        stopped(cancellation);
        verifyResourceUnchanged();
        try (Statement statement = target.createStatement()) {
            long affected = statement.executeLargeUpdate(insertSql(predicate));
            stopped(cancellation);
            if (affected < 0) throw new IllegalStateException("JDBC Resource 导入未返回有效行数，同步进度未推进");
            if (!target.getAutoCommit()) target.commit();
            stopped(cancellation);
            return affected;
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("JDBC Resource INSERT SELECT 导入失败，同步进度未推进", failure);
        }
    }

    private String insertSql(String predicate) {
        return "INSERT INTO " + targetTable + " (" + targetColumns + ") SELECT " + selectColumns
                + " FROM " + externalTable + (predicate == null ? "" : " WHERE " + predicate);
    }

    private String windowPredicate(LocalDate start, LocalDate end) {
        String column = quote(source.timestampColumn);
        return column + " >= '" + start + " 00:00:00' AND " + column + " < '" + end + " 00:00:00'";
    }

    private void verifyResourceUnchanged() throws SQLException {
        if (!resourceProperties.equals(readResource(target, resourceName, cancellation))) {
            throw new IllegalStateException("已有 JDBC Resource 配置发生变化，已停止导入；请确认配置后重试，同步进度未推进");
        }
        stopped(cancellation);
    }

    private void report(long processed, String message) throws SQLException {
        stopped(cancellation);
        if (callback != null) callback.onProgress((int) Math.min(processed, Integer.MAX_VALUE), -1,
                message + "（已导入 " + processed + "）");
        stopped(cancellation);
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        try (DataSyncCancellation.CleanupScope ignored = cancellation == null ? null : cancellation.allowCleanup()) {
            if (createAttempted) {
                try (Statement statement = target.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS " + externalTable);
                } catch (SQLException failure) {
                    throw safe("清理 JDBC Resource 导入外表失败：" + externalTable + "，请清理该外表后重试", failure);
                }
            }
            // The referenced Resource and cached driver belong to the user and are never dropped.
            closed = true;
        }
    }

    private static Map<String, String> readResource(Connection target, String name,
            DataSyncCancellation cancellation) throws SQLException {
        stopped(cancellation);
        Map<String, String> properties = new HashMap<>();
        boolean found = false;
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery("SHOW RESOURCES")) {
            while (rows.next()) {
                stopped(cancellation);
                if (!name.equals(rows.getString("Name"))) continue;
                found = true;
                if (!"jdbc".equalsIgnoreCase(rows.getString("ResourceType"))) {
                    throw new IllegalArgumentException("指定的 Resource 必须为 JDBC 类型，尚未清空目标表");
                }
                String key = rows.getString("Key");
                String value = rows.getString("Value");
                if (key == null || value == null) throw new IllegalArgumentException("已有 JDBC Resource 属性不完整，尚未清空目标表");
                key = key.toLowerCase(Locale.ROOT);
                if ("password".equals(key)) continue;
                String previous = properties.put(key, value);
                if (previous != null && !previous.equals(value)) {
                    throw new IllegalArgumentException("已有 JDBC Resource 属性重复且不一致，尚未清空目标表");
                }
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("无法读取已有 JDBC Resource，请检查名称和访问权限，尚未清空目标表", failure);
        }
        if (!found) throw new IllegalArgumentException("指定的 JDBC Resource 不存在或当前账号无可见权限，尚未清空目标表");
        for (String key : new String[] {"jdbc_uri", "driver_class", "driver_url", "user"}) {
            if (!properties.containsKey(key) || properties.get(key).trim().isEmpty()) {
                throw new IllegalArgumentException("已有 JDBC Resource 缺少必要属性 " + key + "，尚未清空目标表");
            }
        }
        if (properties.containsKey("type") && !"jdbc".equalsIgnoreCase(properties.get("type"))) {
            throw new IllegalArgumentException("已有 JDBC Resource 类型属性不一致，尚未清空目标表");
        }
        properties.put("type", "jdbc");
        stopped(cancellation);
        return properties;
    }

    private static TargetProjection validateTargetColumns(Connection target, String targetTable,
            List<String> targetColumns, StarRocksJdbcResourceSource.Plan source,
            DataSyncCancellation cancellation) throws SQLException {
        if (source.columns.size() != targetColumns.size()) throw new IllegalArgumentException("JDBC Resource 源目标字段数量不一致");
        Map<String, String> types = new HashMap<>();
        Map<Integer, Long> textCapacities = new LinkedHashMap<>();
        Map<Integer, Long> binaryCapacities = new LinkedHashMap<>();
        List<Integer> jsonColumns = new ArrayList<>();
        List<IntegerNarrowing> integerNarrowings = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        boolean jsonProjection = false;
        stopped(cancellation);
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery("DESCRIBE " + targetTable)) {
            while (rows.next()) {
                stopped(cancellation);
                String field = rows.getString("Field");
                String type = rows.getString("Type");
                if (field != null && type != null) types.put(field.toLowerCase(Locale.ROOT), type.trim().toUpperCase(Locale.ROOT));
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("读取 JDBC Resource 导入目标字段类型失败，尚未清空目标表", failure);
        }
        for (int index = 0; index < source.columns.size(); index++) {
            StarRocksJdbcResourceSource.Column column = source.columns.get(index);
            String targetType = types.get(targetColumns.get(index).toLowerCase(Locale.ROOT));
            if (targetType == null) throw new IllegalArgumentException("JDBC Resource 导入缺少目标字段 " + targetColumns.get(index) + "，尚未清空目标表");
            String expression = quote(column.scanName);
            if (column.largeIntegerText) expression = "CAST(" + expression + " AS LARGEINT)";
            if (column.json) {
                if ("JSON".equals(targetType)) {
                    expression = StarRocksExternalFullImport.jsonProjection(quote(column.scanName));
                    // SR 3.1's CONCAT limit includes the two bytes used by the JSON scalar wrapper.
                    textCapacities.put(index, (long) StarRocksExternalFullImport.MAX_JSON_TEXT_BYTES - 2);
                    jsonColumns.add(index);
                    jsonProjection = true;
                } else {
                    long capacity = textCapacity(targetType);
                    if (capacity < 0) throw new IllegalArgumentException("JDBC Resource 源 JSON 字段 " + column.name
                            + " 仅支持写入 JSON 或文本列，尚未清空目标表");
                    textCapacities.put(index, capacity);
                }
            } else if (column.externalTextCapacity >= 0) {
                long capacity = textCapacity(targetType);
                long binaryCapacity = column.binaryHex ? binaryCapacity(targetType) : -1;
                if (binaryCapacity >= 0) {
                    binaryCapacities.put(index, binaryCapacity);
                    textCapacities.put(index, binaryCapacity * 2);
                    expression = binaryProjection(quote(column.scanName), binaryCapacity);
                } else {
                    if (capacity < 0) throw new IllegalArgumentException("JDBC Resource 源文本字段 " + column.name + " 必须写入目标文本列，尚未清空目标表");
                    textCapacities.put(index, capacity);
                }
            } else {
                StarRocksJdbcResourceTargetTypes.IntegerRange range =
                        StarRocksJdbcResourceTargetTypes.integerNarrowing(column, targetType);
                if (range != null) {
                    IntegerNarrowing narrowing = new IntegerNarrowing(quote(column.scanName), range);
                    integerNarrowings.add(narrowing);
                    expression = narrowing.guardedExpression();
                }
            }
            expressions.add(expression);
        }
        stopped(cancellation);
        return new TargetProjection(expressions, textCapacities, binaryCapacities, integerNarrowings, jsonColumns, jsonProjection);
    }

    private static String binaryProjection(String column, long capacity) {
        String decoded = "TO_BINARY(" + column + ", 'hex')";
        return "IF(ASSERT_TRUE(" + column + " IS NULL OR (LENGTH(" + column + ") <= " + (capacity * 2)
                + " AND IFNULL(HEX(" + decoded + ") = UPPER(" + column + "), FALSE)), 'binary hex conversion failed'), "
                + decoded + ", NULL)";
    }

    private static long binaryCapacity(String type) {
        Matcher matcher = BINARY_CAPACITY.matcher(type);
        if (!matcher.matches()) {
            if (type.startsWith("BINARY") || type.startsWith("VARBINARY")) {
                throw new IllegalArgumentException("JDBC Resource 目标二进制字段容量无效（须为 1 至 1048576 字节），尚未清空目标表");
            }
            return -1;
        }
        if (matcher.group(1) == null) return MAX_BINARY_CAPACITY;
        try {
            long capacity = Long.parseLong(matcher.group(1));
            if (capacity >= 1 && capacity <= MAX_BINARY_CAPACITY) return capacity;
        } catch (NumberFormatException invalidCapacity) {
            // Invalid metadata must not silently use a default size.
        }
        throw new IllegalArgumentException("JDBC Resource 目标二进制字段容量无效（须为 1 至 1048576 字节），尚未清空目标表");
    }

    private static long textCapacity(String type) {
        if ("STRING".equals(type) || "VARCHAR".equals(type)) return DEFAULT_TEXT_CAPACITY;
        Matcher matcher = TEXT_CAPACITY.matcher(type);
        if (!matcher.matches()) return -1;
        try {
            long value = Long.parseLong(matcher.group(1));
            if (value > 0) return value;
        } catch (NumberFormatException invalid) {
            // Invalid target metadata must not allow implicit narrowing.
        }
        throw new IllegalArgumentException("JDBC Resource 目标文本字段容量无效，尚未清空目标表");
    }

    private static void validateColumns(List<String> source, List<String> target) {
        if (source == null || target == null || source.isEmpty() || source.size() != target.size()) {
            throw new IllegalArgumentException("JDBC Resource 源目标字段配置无效，尚未清空目标表");
        }
        for (List<String> columns : Arrays.asList(source, target)) {
            Set<String> seen = new HashSet<>();
            for (String column : columns) {
                quote(column);
                if (!seen.add(column.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("JDBC Resource 字段名称重复，尚未清空目标表");
            }
        }
    }

    private static String joinColumns(List<String> columns) {
        List<String> quoted = new ArrayList<>();
        for (String column : columns) quoted.add(quote(column));
        return String.join(", ", quoted);
    }

    private static String quote(String identifier) { return StarRocksExternalFullImport.quoteIdentifier(identifier); }
    private static String property(String value) { return StarRocksExternalFullImport.property(value); }
    private static void stopped(DataSyncCancellation cancellation) throws DataSyncCancellation.SyncStoppedException {
        if (cancellation != null) cancellation.checkStopped();
    }

    private static SQLException safe(String operation, SQLException failure) {
        if (failure instanceof DataSyncCancellation.SyncStoppedException) return failure;
        return new ResourceSQLException(operation, failure);
    }

    private static final class ResourceSQLException extends SQLException {
        ResourceSQLException(String operation, SQLException failure) {
            super(operation + "（SQLState=" + failure.getSQLState() + ", errorCode=" + failure.getErrorCode()
                    + "）", failure.getSQLState(), failure.getErrorCode());
        }
    }

    private static final class TargetProjection {
        final String sql;
        final List<String> expressions;
        final Map<Integer, Long> textCapacities;
        final Map<Integer, Long> binaryCapacities;
        final List<Integer> jsonColumns;
        final List<IntegerNarrowing> integerNarrowings;
        final boolean json;
        TargetProjection(List<String> expressions, Map<Integer, Long> textCapacities, Map<Integer, Long> binaryCapacities,
                List<IntegerNarrowing> integerNarrowings, List<Integer> jsonColumns, boolean json) {
            this.expressions = new ArrayList<>(expressions);
            this.sql = String.join(", ", expressions);
            this.textCapacities = textCapacities;
            this.binaryCapacities = new LinkedHashMap<>(binaryCapacities);
            this.jsonColumns = new ArrayList<>(jsonColumns);
            this.integerNarrowings = integerNarrowings;
            this.json = json;
        }
    }

    private static final class IntegerNarrowing {
        final String column;
        final String expression;
        final StarRocksJdbcResourceTargetTypes.IntegerRange range;

        IntegerNarrowing(String column, StarRocksJdbcResourceTargetTypes.IntegerRange range) {
            this.column = column;
            this.expression = "CAST(" + column + " AS LARGEINT)";
            this.range = range;
        }

        String guardedExpression() {
            // assert_true returns true (numeric 1) or throws. Keep the guard in SELECT,
            // never in WHERE: source dialects do not implement StarRocks assert_true.
            return "CAST(assert_true(" + column + " IS NULL OR (" + expression + " >= " + range.minimum
                    + " AND " + expression + " <= " + range.maximum
                    + "), 'numeric range overflow') AS LARGEINT) * " + expression;
        }

        BigInteger readInteger(String value) {
            if (value == null) return null;
            String integer = value.trim();
            if (integer.matches("[+-]?[0-9]{1,39}")) return new BigInteger(integer);
            throw new IllegalArgumentException("JDBC Resource 字段 " + range.columnName
                    + " 的实际整数范围格式无效，尚未清空目标表");
        }
    }

    private enum WindowUnit {
        YEAR("年"), MONTH("月"), DAY("日");
        final String label;
        WindowUnit(String label) { this.label = label; }
        static WindowUnit of(String value) {
            if (value == null) return YEAR;
            try { return valueOf(value); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("JDBC Resource 时间单位仅支持 YEAR、MONTH 或 DAY"); }
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
