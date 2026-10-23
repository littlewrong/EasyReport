package cn.easyreport.sync.datasync;

import cn.easyreport.sync.core.SyncProgressCallback;
import cn.easyreport.sync.domain.ErDatasource;

import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-table MYSQL external table, owned and removed by a single full import. */
final class StarRocksExternalFullImport implements AutoCloseable {
    static final String IMPORT_DATABASE = "easyreport_import_tmp";
    static final int DEFAULT_THRESHOLD_WAN = 50;
    static final int MAX_JSON_TEXT_BYTES = 1_048_576;
    private static final int SR_DEFAULT_STRING_BYTES = 65_533;
    private static final String JSON_CONVERSION_ERROR = "EasyReport JSON import conversion failed";
    static final String JSON_CONVERSION_PROBE_SQL = "SELECT JSON_STRING(" + jsonProjection("'123'")
            + "), JSON_STRING(" + jsonProjection("'true'") + "), JSON_STRING(" + jsonProjection("'null'")
            + "), JSON_STRING(" + jsonProjection("CAST(NULL AS VARCHAR)") + ")";
    private static final Pattern MYSQL_URL = Pattern.compile(
            "^jdbc:mysql://(\\[[0-9a-fA-F:]+\\]|[a-zA-Z0-9._-]+)(?::([0-9]+))?/[^?]*(?:\\?(.*))?$");
    private static final String SQL_IDENTIFIER = "(?:`(?:``|[^`])+`|[a-zA-Z0-9_$\\u0080-\\uFFFF]+)";
    private static final Pattern TARGET_TABLE = Pattern.compile("^" + SQL_IDENTIFIER + "(?:\\." + SQL_IDENTIFIER + ")?$");
    private static final Pattern TARGET_VARCHAR = Pattern.compile("^VARCHAR\\s*\\(\\s*([0-9]+)\\s*\\)$");

    private final Connection targetConn;
    private final String externalTable;
    private final String targetTable;
    private final String sourceColumnSql;
    private final String targetColumnSql;
    private final String timestampColumn;
    private final Timestamp maxTs;
    private final LocalDate windowStart;
    private final LocalDate windowEndExclusive;
    private final ImportWindowUnit windowUnit;
    private final long sourceRowCount;
    private final SyncProgressCallback callback;
    private final DataSyncCancellation cancellation;
    private boolean createAttempted;
    private boolean closed;
    private boolean executed;

    private StarRocksExternalFullImport(Connection targetConn, String targetTable,
            String sourceSelectSql, List<String> targetCols, String timestampColumn,
            Timestamp maxTs, LocalDate windowStart, LocalDate windowEndExclusive,
            ImportWindowUnit windowUnit, Long transferId,
            long sourceRowCount, SyncProgressCallback callback, DataSyncCancellation cancellation) {
        this.targetConn = targetConn;
        this.externalTable = quoteIdentifier(IMPORT_DATABASE) + "." + quoteIdentifier(
                "er_import_" + (transferId == null ? "0" : Long.toUnsignedString(transferId))
                        + "_" + UUID.randomUUID().toString().replace("-", ""));
        this.targetTable = targetTable;
        this.sourceColumnSql = sourceSelectSql;
        this.targetColumnSql = joinColumns(targetCols);
        this.timestampColumn = timestampColumn;
        this.maxTs = maxTs;
        this.windowStart = windowStart;
        this.windowEndExclusive = windowEndExclusive;
        this.windowUnit = windowUnit;
        this.sourceRowCount = sourceRowCount;
        this.callback = callback;
        this.cancellation = cancellation;
    }

    static StarRocksExternalFullImport prepare(ErDatasource sourceDs, Connection sourceConn,
            Connection targetConn, String schema, String sourceTable, String targetTableSql,
            List<String> sourceCols, List<String> targetCols, String tsField, Long transferId,
            int windowDays, SyncProgressCallback callback) throws Exception {
        return prepare(sourceDs, sourceConn, targetConn, schema, sourceTable, targetTableSql,
                sourceCols, targetCols, tsField, transferId, windowDays, callback, null);
    }

    static StarRocksExternalFullImport prepare(ErDatasource sourceDs, Connection sourceConn,
            Connection targetConn, String schema, String sourceTable, String targetTableSql,
            List<String> sourceCols, List<String> targetCols, String tsField, Long transferId,
            int windowDays, SyncProgressCallback callback, DataSyncCancellation cancellation) throws Exception {
        return prepare(sourceDs, sourceConn, targetConn, schema, sourceTable, targetTableSql,
                sourceCols, targetCols, tsField, transferId, null, null, callback, cancellation);
    }

    static StarRocksExternalFullImport prepare(ErDatasource sourceDs, Connection sourceConn,
            Connection targetConn, String schema, String sourceTable, String targetTableSql,
            List<String> sourceCols, List<String> targetCols, String tsField, Long transferId,
            Integer thresholdWan, String unit, SyncProgressCallback callback,
            DataSyncCancellation cancellation) throws Exception {
        try {
            return prepareImport(sourceDs, sourceConn, targetConn, schema, sourceTable, targetTableSql,
                    sourceCols, targetCols, tsField, transferId, thresholdWan, unit, callback, cancellation);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw safeValidationException(failure);
        }
    }

    private static StarRocksExternalFullImport prepareImport(ErDatasource sourceDs, Connection sourceConn,
            Connection targetConn, String schema, String sourceTable, String targetTableSql,
            List<String> sourceCols, List<String> targetCols, String tsField, Long transferId,
            Integer thresholdWan, String unit, SyncProgressCallback callback,
            DataSyncCancellation cancellation) throws Exception {
        checkStopped(cancellation);
        int effectiveThresholdWan = thresholdWan == null ? DEFAULT_THRESHOLD_WAN : thresholdWan;
        if (effectiveThresholdWan <= 0) {
            throw new IllegalArgumentException("外部表整批导入阈值必须为正整数，单位为万条");
        }
        long rowThreshold = effectiveThresholdWan * 10_000L;
        ImportWindowUnit windowUnit = ImportWindowUnit.parse(unit);
        validateColumns(sourceCols, targetCols);
        if (targetTableSql == null || !TARGET_TABLE.matcher(targetTableSql).matches()
                || targetTableSql.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("外部表导入的目标表名格式无效");
        }
        quoteIdentifier(sourceTable);
        Endpoint endpoint = resolveEndpoint(sourceDs, sourceConn);
        checkStopped(cancellation);
        String sourceSchema = schema;
        if (sourceSchema == null || sourceSchema.trim().isEmpty()) {
            try {
                sourceSchema = sourceConn.getCatalog();
            } catch (SQLException ex) {
                checkStopped(cancellation);
                throw safeSqlException("无法确定源库名称", ex);
            }
        }
        if (sourceSchema == null || sourceSchema.trim().isEmpty()) sourceSchema = sourceDs.getDatabaseName();
        quoteIdentifier(sourceSchema);

        Map<String, Column> metadata = readColumns(sourceConn, sourceSchema, sourceTable);
        checkStopped(cancellation);
        StringBuilder columns = new StringBuilder();
        List<Integer> jsonIndexes = new ArrayList<>();
        boolean hasTimestamp = false;
        for (int index = 0; index < sourceCols.size(); index++) {
            String name = sourceCols.get(index);
            Column column = metadata.get(name.toLowerCase(Locale.ROOT));
            if (column == null) throw new IllegalArgumentException("源字段元数据不完整，无法安全创建导入外表");
            if (columns.length() > 0) columns.append(", ");
            columns.append(quoteIdentifier(name)).append(' ').append(mapType(column))
                    .append(column.nullable ? " NULL" : " NOT NULL");
            hasTimestamp |= "timestamp".equals(column.dataType);
            if ("json".equals(column.dataType)) jsonIndexes.add(index);
        }
        if (hasTimestamp) verifyTimestampTimeZone(sourceConn);
        checkStopped(cancellation);
        String timestampColumn = null;
        if (tsField != null && !tsField.trim().isEmpty()) {
            for (String name : sourceCols) {
                if (name.equalsIgnoreCase(tsField.trim())) timestampColumn = name;
            }
            if (timestampColumn == null) throw new IllegalArgumentException("配置的时间戳字段不在源表字段中");
            Column column = metadata.get(timestampColumn.toLowerCase(Locale.ROOT));
            if (!"date".equals(column.dataType) && !"datetime".equals(column.dataType)
                    && !"timestamp".equals(column.dataType)) {
                throw new IllegalArgumentException("外部表按时间窗口导入仅支持 DATE、DATETIME 或 TIMESTAMP 时间戳字段");
            }
        }

        Timestamp min = null;
        Timestamp max = null;
        LocalDate minDay = null;
        LocalDate maxDay = null;
        LocalDate windowStart = null;
        LocalDate windowEndExclusive = null;
        String qualifiedSource = quoteIdentifier(sourceSchema) + "." + quoteIdentifier(sourceTable);
        String sourceFilter = timestampColumn == null ? ""
                : " WHERE " + quoteIdentifier(timestampColumn) + " IS NOT NULL";
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*)");
        for (int index : jsonIndexes) {
            countSql.append(", MAX(OCTET_LENGTH(CAST(").append(quoteIdentifier(sourceCols.get(index)))
                    .append(" AS CHAR CHARACTER SET utf8mb4)))");
        }
        countSql.append(" FROM ").append(qualifiedSource).append(sourceFilter);
        Map<Integer, Long> jsonMaxBytes = new HashMap<>();
        long sourceRowCount;
        try (Statement statement = sourceConn.createStatement();
                ResultSet rows = statement.executeQuery(countSql.toString())) {
            if (!rows.next()) throw new IllegalStateException("读取源表准确行数失败：COUNT 查询未返回结果");
            sourceRowCount = rows.getLong(1);
            if (rows.wasNull() || sourceRowCount < 0) {
                throw new IllegalStateException("读取源表准确行数失败：COUNT 查询返回无效行数");
            }
            for (int index = 0; index < jsonIndexes.size(); index++) {
                long bytes = rows.getLong(index + 2);
                if (rows.wasNull()) bytes = 0;
                if (bytes < 0) throw new IllegalStateException("读取源 JSON 最大字节数失败，尚未清空目标表");
                jsonMaxBytes.put(jsonIndexes.get(index), bytes);
            }
        } catch (SQLException ex) {
            checkStopped(cancellation);
            throw safeSqlException("读取源表准确行数失败，尚未清空目标表", ex);
        }
        checkStopped(cancellation);
        if (timestampColumn != null && sourceRowCount > 0) {
            String ts = quoteIdentifier(timestampColumn);
            try (Statement statement = sourceConn.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT MIN(" + ts + "), MAX(" + ts + ") FROM "
                            + qualifiedSource + sourceFilter)) {
                if (!rows.next()) {
                    throw new IllegalStateException("源表存在有效时间戳数据，但时间戳范围查询未返回结果，尚未清空目标表");
                }
                min = rows.getTimestamp(1);
                max = rows.getTimestamp(2);
                if (min == null || max == null) {
                    throw new IllegalStateException("源表存在有效时间戳数据，但未获取有效时间戳范围，尚未清空目标表");
                }
                // MYSQL external scans use source-session textual dates. Do not apply the JVM time zone.
                minDay = sourceDay(rows.getString(1));
                maxDay = sourceDay(rows.getString(2));
            } catch (SQLException ex) {
                checkStopped(cancellation);
                throw safeSqlException("读取源表时间戳范围失败", ex);
            }
            checkStopped(cancellation);
            if (minDay.isAfter(maxDay) || min.after(max)) {
                throw new IllegalStateException("源表时间戳范围无效，尚未清空目标表");
            }
            if (sourceRowCount >= rowThreshold) {
                // Validate both ends before the caller can clear the destination. Every intermediate
                // natural window is between these valid, monotonically increasing boundaries.
                windowStart = windowUnit.startOf(minDay);
                windowEndExclusive = windowUnit.next(windowUnit.startOf(maxDay));
                if (windowStart.getYear() < 1 || windowEndExclusive.getYear() > 9999) {
                    throw new UnsupportedImportException("源时间戳无法安全构造完整自然" + windowUnit.label
                            + "边界，使用 JDBC 导入方式");
                }
            }
        }

        String sourceSelectSql = prepareSourceProjection(targetConn, targetTableSql, sourceCols, targetCols,
                jsonIndexes, jsonMaxBytes, cancellation);
        StarRocksExternalFullImport importer = new StarRocksExternalFullImport(targetConn,
                targetTableSql, sourceSelectSql, targetCols, timestampColumn, max, windowStart,
                windowEndExclusive, windowUnit, transferId, sourceRowCount, callback, cancellation);
        try {
            importer.checkStopped();
            try (Statement statement = targetConn.createStatement()) {
                statement.execute("SET enable_insert_strict = true");
                importer.checkStopped();
                try (ResultSet rows = statement.executeQuery("SHOW VARIABLES LIKE 'enable_insert_strict'")) {
                    if (!rows.next() || !isTrue(rows.getString(2))) {
                        throw new IllegalStateException("StarRocks 严格导入模式未启用，已取消导入");
                    }
                }
                importer.checkStopped();
                // The caller owns a dedicated task connection; session settings expire when it closes.
                long timeout;
                try (ResultSet rows = statement.executeQuery("SHOW VARIABLES LIKE 'query_timeout'")) {
                    if (!rows.next()) throw new IllegalStateException("无法确认 StarRocks 导入会话的 query_timeout");
                    timeout = rows.getLong(2);
                }
                importer.checkStopped();
                if (timeout < 3600) statement.execute("SET SESSION query_timeout = 3600");
                importer.checkStopped();
                statement.execute("CREATE DATABASE IF NOT EXISTS " + quoteIdentifier(IMPORT_DATABASE));
                importer.checkStopped();
                String createSql = "CREATE EXTERNAL TABLE " + importer.externalTable + " (" + columns
                        + ") ENGINE=MYSQL PROPERTIES (\"host\"=" + property(endpoint.host)
                        + ",\"port\"=" + property(String.valueOf(endpoint.port))
                        + ",\"user\"=" + property(sourceDs.getUsername())
                        + ",\"password\"=" + property(sourceDs.getPassword())
                        + ",\"database\"=" + property(sourceSchema)
                        + ",\"table\"=" + property(sourceTable) + ")";
                importer.createAttempted = true;
                statement.execute(createSql);
                importer.checkStopped();
                try (ResultSet rows = statement.executeQuery("SELECT " + importer.sourceColumnSql
                        + " FROM " + importer.externalTable + sourceFilter + " LIMIT 1")) {
                    if (rows.next()) {
                        for (int i = 1; i <= sourceCols.size(); i++) rows.getObject(i);
                    }
                }
                importer.checkStopped();
                try (ResultSet plan = statement.executeQuery("EXPLAIN INSERT INTO " + importer.targetTable
                        + " (" + importer.targetColumnSql + ") SELECT " + importer.sourceColumnSql
                        + " FROM " + importer.externalTable + " WHERE 1=0")) {
                    while (plan.next()) {
                        importer.checkStopped();
                        // Consume the plan before allowing the caller to clear the target.
                    }
                }
                importer.checkStopped();
            } catch (SQLException ex) {
                importer.checkStopped();
                // A JDBC driver can echo the CREATE statement, including the source password.
                throw safeSqlException("StarRocks 导入外表创建或读取预校验失败，请检查权限、类型和 BE 到源库的连接", ex);
            }
            return importer;
        } catch (Exception failure) {
            if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
                failure = safeValidationException((RuntimeException) failure);
            }
            try {
                importer.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static String prepareSourceProjection(Connection targetConn, String targetTable,
            List<String> sourceCols, List<String> targetCols, List<Integer> jsonIndexes,
            Map<Integer, Long> jsonMaxBytes, DataSyncCancellation cancellation) throws SQLException {
        List<String> expressions = new ArrayList<>();
        for (String column : sourceCols) expressions.add(quoteIdentifier(column));
        if (jsonIndexes.isEmpty()) return String.join(", ", expressions);

        checkStopped(cancellation);
        Map<String, String> targetTypes = new HashMap<>();
        // SR 3.1 reports JSON as MYSQL_TYPE_STRING in result metadata. DESCRIBE gives the stored type.
        try (Statement statement = targetConn.createStatement();
                ResultSet rows = statement.executeQuery("DESCRIBE " + targetTable)) {
            while (rows.next()) {
                checkStopped(cancellation);
                String name = rows.getString("Field");
                String type = rows.getString("Type");
                if (name != null) {
                    targetTypes.put(name.toLowerCase(Locale.ROOT), type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
                }
            }
        } catch (SQLException ex) {
            checkStopped(cancellation);
            throw safeSqlException("读取 JSON 导入目标字段类型失败，尚未清空目标表", ex);
        }
        boolean hasJsonTarget = false;
        for (int index : jsonIndexes) {
            checkStopped(cancellation);
            String targetColumn = targetCols.get(index);
            String type = targetTypes.get(targetColumn.toLowerCase(Locale.ROOT));
            if (type == null) {
                throw new IllegalArgumentException("无法找到源 JSON 字段 " + sourceCols.get(index)
                        + " 对应的目标字段 " + targetColumn + "，尚未清空目标表");
            }
            long externalLimit = MAX_JSON_TEXT_BYTES;
            if ("JSON".equals(type)) {
                // CONCAT returns NULL above 1 MiB in SR 3.1; reserve two bytes for the array wrapper.
                externalLimit -= 2;
                expressions.set(index, jsonProjection(quoteIdentifier(sourceCols.get(index))));
                hasJsonTarget = true;
            } else if ("STRING".equals(type) || type.startsWith("VARCHAR")) {
                long targetLimit = SR_DEFAULT_STRING_BYTES;
                if (!"STRING".equals(type)) {
                    Matcher length = TARGET_VARCHAR.matcher(type);
                    try {
                        if (!length.matches()) throw new NumberFormatException();
                        targetLimit = Long.parseLong(length.group(1));
                        if (targetLimit <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException invalid) {
                        throw new IllegalArgumentException("无法确认源 JSON 字段 " + sourceCols.get(index)
                                + " 对应目标 VARCHAR 字段 " + targetColumn + " 的字节容量，尚未清空目标表");
                    }
                }
                if (jsonMaxBytes.get(index) > targetLimit) {
                    throw new IllegalArgumentException("源 JSON 字段 " + sourceCols.get(index)
                            + " 的文本超出目标字段 " + targetColumn + " 的容量（"
                            + targetLimit + " 字节），尚未清空目标表");
                }
            } else {
                throw new IllegalArgumentException("源 JSON 字段 " + sourceCols.get(index)
                        + " 只支持写入 StarRocks JSON、STRING 或 VARCHAR 列，请检查目标字段：" + targetColumn);
            }
            if (jsonMaxBytes.get(index) > externalLimit) {
                throw new UnsupportedImportException("源 JSON 字段 " + sourceCols.get(index)
                        + " 的文本超出外表安全长度（" + externalLimit + " 字节），使用 JDBC 导入方式");
            }
        }
        checkStopped(cancellation);
        if (hasJsonTarget) verifyJsonProjection(targetConn, cancellation);
        return String.join(", ", expressions);
    }

    static String jsonProjection(String rawSql) {
        // SR 3.1/3.2 PARSE_JSON treats top-level numeric/bool/null tokens as strings. Parsing an array
        // and extracting its first element preserves their JSON types, including JSON null vs SQL NULL.
        String parsed = "JSON_QUERY(PARSE_JSON(CONCAT('[', " + rawSql + ", ']')), '$[0]')";
        // JSON IS NOT NULL also rejects JSON null in these versions. Serialize only for the guard:
        // JSON null becomes the non-NULL text 'null', while a failed conversion remains SQL NULL.
        return "IF(ASSERT_TRUE(" + rawSql + " IS NULL OR JSON_STRING(" + parsed + ") IS NOT NULL, '"
                + JSON_CONVERSION_ERROR + "'), " + parsed + ", NULL)";
    }

    static void verifyJsonProjection(Connection targetConn, DataSyncCancellation cancellation) throws SQLException {
        checkStopped(cancellation);
        try (Statement statement = targetConn.createStatement();
                ResultSet rows = statement.executeQuery(JSON_CONVERSION_PROBE_SQL)) {
            if (!rows.next()) throw new IllegalStateException("StarRocks JSON 导入表达式校验未返回结果，尚未清空目标表");
            String[] cases = {"数字标量", "布尔标量", "JSON null", "SQL NULL"};
            String[] expected = {"123", "true", "null", null};
            for (int index = 0; index < expected.length; index++) {
                checkStopped(cancellation);
                String actual = rows.getString(index + 1);
                if (expected[index] == null ? actual != null : !expected[index].equals(actual)) {
                    throw new IllegalStateException("StarRocks JSON 导入表达式未保留标量或 NULL 语义（校验项："
                            + cases[index] + "，预期：" + (expected[index] == null ? "SQL NULL" : "'" + expected[index] + "'")
                            + "，实际：" + (actual == null ? "SQL NULL" : "'" + actual + "'") + "），尚未清空目标表");
                }
            }
        } catch (SQLException ex) {
            checkStopped(cancellation);
            throw safeSqlException("StarRocks JSON 导入表达式预校验失败，尚未清空目标表", ex);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw safeValidationException(failure);
        }
        checkStopped(cancellation);
    }

    Result execute() throws Exception {
        checkStopped();
        if (closed || executed) throw new IllegalStateException("导入外表已关闭或已执行");
        executed = true;
        long processed = 0;
        String sourceRows = timestampColumn == null ? "源表 " + sourceRowCount + " 行"
                : "源表有效非 NULL 行数 " + sourceRowCount + "，跳过时间戳为 NULL 的数据";
        if (timestampColumn != null && sourceRowCount == 0) {
            report(0, sourceRows + "，无需导入");
            checkStopped();
            return new Result(0, null);
        }
        boolean windowedImport = windowStart != null;
        report(0, sourceRows + "，采用" + (windowedImport ? "按自然" + windowUnit.label + "导入" : "整批导入"));
        if (!windowedImport) {
            processed = insert(timestampColumn == null ? null : quoteIdentifier(timestampColumn) + " IS NOT NULL");
            report(processed, "整批导入，" + sourceRows);
        } else {
            LocalDate start = windowStart;
            while (start.isBefore(windowEndExclusive)) {
                checkStopped();
                LocalDate end = windowUnit.next(start);
                String ts = quoteIdentifier(timestampColumn);
                processed = Math.addExact(processed, insert(ts + " >= '" + start + " 00:00:00' AND "
                        + ts + " < '" + end + " 00:00:00'"));
                report(processed, "按" + windowUnit.label + "导入 [" + start + ", " + end + ")，" + sourceRows);
                start = end;
            }
        }
        checkStopped();
        return new Result(processed, maxTs);
    }

    private long insert(String predicate) throws Exception {
        checkStopped();
        String sql = "INSERT INTO " + targetTable + " (" + targetColumnSql + ") SELECT "
                + sourceColumnSql + " FROM " + externalTable
                + (predicate == null ? "" : " WHERE " + predicate);
        try (Statement statement = targetConn.createStatement()) {
            long affected = statement.executeLargeUpdate(sql);
            checkStopped();
            if (affected < 0) throw new IllegalStateException("StarRocks 未返回有效导入行数，已停止推进同步进度");
            if (!targetConn.getAutoCommit()) targetConn.commit();
            checkStopped();
            return affected;
        } catch (SQLException ex) {
            checkStopped();
            throw safeSqlException("StarRocks INSERT SELECT 导入失败，同步进度未推进", ex);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw safeValidationException(failure);
        }
    }

    private void report(long processed, String message) throws SQLException {
        checkStopped();
        if (callback != null) callback.onProgress((int) Math.min(processed, Integer.MAX_VALUE), -1,
                message + " (" + processed + ")");
    }

    private void checkStopped() throws DataSyncCancellation.SyncStoppedException {
        checkStopped(cancellation);
    }

    private static void checkStopped(DataSyncCancellation cancellation)
            throws DataSyncCancellation.SyncStoppedException {
        if (cancellation != null) {
            cancellation.checkStopped();
        }
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        try (DataSyncCancellation.CleanupScope ignored = cancellation == null ? null : cancellation.allowCleanup()) {
            if (createAttempted) {
                try (Statement statement = targetConn.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS " + externalTable);
                } catch (SQLException ex) {
                    throw safeSqlException("清理导入外表失败：" + externalTable + "，请清理该外表后重试", ex);
                }
            }
            closed = true;
        }
    }

    private static Map<String, Column> readColumns(Connection sourceConn, String schema, String table) throws SQLException {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, IS_NULLABLE"
                + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        Map<String, Column> result = new HashMap<>();
        try (PreparedStatement statement = sourceConn.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Column column = new Column(rows.getString("DATA_TYPE"), rows.getString("COLUMN_TYPE"),
                            rows.getInt("NUMERIC_PRECISION"), rows.getInt("NUMERIC_SCALE"), rows.getInt("DATETIME_PRECISION"),
                            !"NO".equalsIgnoreCase(rows.getString("IS_NULLABLE")));
                    result.put(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), column);
                }
            }
        } catch (SQLException ex) {
            throw safeSqlException("读取源表字段元数据失败", ex);
        }
        return result;
    }

    static String mapType(Column column) {
        String type = column.dataType;
        boolean unsigned = column.columnType.contains("unsigned");
        switch (type) {
            case "json": return "VARCHAR(" + MAX_JSON_TEXT_BYTES + ")";
            case "char": case "varchar": case "tinytext": case "text": case "mediumtext": case "longtext":
            case "enum": case "set": return "VARCHAR(65533)";
            case "tinyint": return unsigned ? "SMALLINT" : "TINYINT";
            case "smallint": return unsigned ? "INT" : "SMALLINT";
            case "mediumint": return "INT";
            case "int": case "integer": return unsigned ? "BIGINT" : "INT";
            case "bigint": return unsigned ? "DECIMAL(20,0)" : "BIGINT";
            case "boolean": case "bool": return "TINYINT";
            case "year": return "SMALLINT";
            case "float": return "FLOAT";
            case "double": case "real": return "DOUBLE";
            case "decimal": case "numeric":
                if (column.precision > 38) {
                    throw new UnsupportedImportException("源 DECIMAL 精度超出 StarRocks 外表安全映射范围（最大 38 位）");
                }
                if (column.precision < 1 || column.scale < 0 || column.scale > column.precision) {
                    throw new IllegalArgumentException("源 DECIMAL 字段的精度或小数位元数据无效");
                }
                return "DECIMAL(" + column.precision + "," + column.scale + ")";
            case "date": return "DATE";
            // Match the existing SMT path; fractional seconds follow the SR 3.1 DATETIME target precision.
            case "datetime": case "timestamp": return "DATETIME";
            case "bit":
                // MySQL exposes BIT as binary bytes, while the 3.1 MYSQL scanner parses numbers as text.
                throw new UnsupportedImportException("源 BIT 字段不能通过 MYSQL 外表安全解析，使用 JDBC 导入方式");
            default:
                throw new UnsupportedImportException("源字段类型 " + type + " 没有经过安全的 MYSQL 外表映射，使用 JDBC 导入方式");
        }
    }

    private static Endpoint resolveEndpoint(ErDatasource sourceDs, Connection sourceConn) throws Exception {
        if (sourceDs == null || sourceDs.getUsername() == null) throw new IllegalArgumentException("源数据源连接配置不完整");
        Endpoint configured = parseEndpoint(sourceDs.getJdbcUrl());
        if (sourceDs.getHost() != null && !sourceDs.getHost().trim().isEmpty()
                && !normalizeHost(sourceDs.getHost()).equalsIgnoreCase(normalizeHost(configured.host))) {
            throw new IllegalArgumentException("源数据源 host 与 JDBC URL 不一致，已取消外表导入");
        }
        if (sourceDs.getPort() != null && sourceDs.getPort() != configured.port) {
            throw new IllegalArgumentException("源数据源 port 与 JDBC URL 不一致，已取消外表导入");
        }
        String actualUrl;
        try {
            actualUrl = sourceConn.getMetaData().getURL();
        } catch (SQLException ex) {
            throw safeSqlException("无法核对源 JDBC 连接地址", ex);
        }
        if (actualUrl != null && !actualUrl.trim().isEmpty()) {
            Endpoint actual = parseEndpoint(actualUrl);
            if (!normalizeHost(actual.host).equalsIgnoreCase(normalizeHost(configured.host)) || actual.port != configured.port) {
                throw new IllegalArgumentException("实际源连接与数据源地址不一致，已取消外表导入");
            }
        }
        return configured;
    }

    static Endpoint parseEndpoint(String url) throws Exception {
        Matcher matcher = MYSQL_URL.matcher(url == null ? "" : url);
        if (!matcher.matches()) throw new IllegalArgumentException("外表导入仅支持单节点 jdbc:mysql://host:port/database 连接");
        int port;
        try {
            port = matcher.group(2) == null ? 3306 : Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("源 JDBC 端口无效");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("源 JDBC 端口无效");
        String query = matcher.group(3);
        String sslMode = null;
        boolean useSsl = true;
        boolean requireSsl = false;
        boolean verifyCertificate = false;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);
                String key;
                String value;
                try {
                    key = URLDecoder.decode(pair[0], "UTF-8").toLowerCase(Locale.ROOT);
                    value = pair.length > 1 ? URLDecoder.decode(pair[1], "UTF-8") : "";
                } catch (IllegalArgumentException ex) {
                    // URLDecoder's original message can contain URL or credential fragments.
                    throw new IllegalArgumentException("源 JDBC URL 参数编码无效");
                }
                if (isConnectionOverride(key)) {
                    throw new IllegalArgumentException("源 JDBC URL 包含连接覆盖参数，无法安全复用至外表");
                }
                if ("sessionvariables".equals(key) || ("forceconnectiontimezonetosession".equals(key)
                        && ("true".equalsIgnoreCase(value) || "1".equals(value)))) {
                    throw new UnsupportedImportException("源 JDBC 使用独立会话设置，MYSQL 外表无法复用，使用 JDBC 导入方式");
                }
                if ("sslmode".equals(key)) sslMode = value;
                if ("usessl".equals(key)) useSsl = isTrue(value);
                if ("requiressl".equals(key)) requireSsl = isTrue(value);
                if ("verifyservercertificate".equals(key)) verifyCertificate = isTrue(value);
            }
        }
        // Connector/J 8: useSSL=true alone means PREFERRED; explicit sslMode overrides legacy flags.
        boolean requiresTls = sslMode != null
                ? !"DISABLED".equalsIgnoreCase(sslMode) && !"PREFERRED".equalsIgnoreCase(sslMode)
                : useSsl && (requireSsl || verifyCertificate);
        if (requiresTls) {
            throw new UnsupportedImportException("源 JDBC 要求 TLS 连接，MYSQL 外表无法复用该安全配置，使用 JDBC 导入方式");
        }
        return new Endpoint(normalizeHost(matcher.group(1)), port);
    }

    private static boolean isConnectionOverride(String key) {
        switch (key) {
            case "user": case "username": case "password": case "password1": case "password2": case "password3":
            case "host": case "port": case "address": case "protocol": case "dbname": case "database":
            case "socketfactory": case "socketfactoryarg": case "socketfactoryclassname":
            case "socksproxyhost": case "socksproxyport": case "localsocketaddress":
            case "dnssrv": case "dnssrvservicename": case "propertiestransform": case "useconfigs":
            case "connectionlifecycleinterceptors": case "queryinterceptors": return true;
            default: return false;
        }
    }

    private static void verifyTimestampTimeZone(Connection sourceConn) throws SQLException {
        try (Statement statement = sourceConn.createStatement();
                ResultSet rows = statement.executeQuery("SELECT @@session.time_zone, @@global.time_zone")) {
            if (!rows.next()) throw new IllegalStateException("无法确认源 TIMESTAMP 会话时区");
            String sessionZone = rows.getString(1);
            String globalZone = rows.getString(2);
            if (sessionZone == null || globalZone == null || !sessionZone.equalsIgnoreCase(globalZone)) {
                throw new UnsupportedImportException("源 TIMESTAMP 会话时区与全局时区不同，MYSQL 外表无法复用，使用 JDBC 导入方式");
            }
        } catch (SQLException ex) {
            throw safeSqlException("读取源 TIMESTAMP 会话时区失败", ex);
        }
    }

    private static LocalDate sourceDay(String value) {
        try {
            if (value == null || value.length() < 10) throw new IllegalArgumentException();
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("源时间戳日期格式无效，无法安全确定导入时间窗口");
        }
    }

    private enum ImportWindowUnit {
        YEAR("年"), MONTH("月"), DAY("日");

        private final String label;

        ImportWindowUnit(String label) {
            this.label = label;
        }

        static ImportWindowUnit parse(String value) {
            if (value == null) return YEAR;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("外部表导入时间单位仅支持 YEAR、MONTH 或 DAY");
            }
        }

        LocalDate startOf(LocalDate date) {
            if (this == YEAR) return date.withDayOfYear(1);
            if (this == MONTH) return date.withDayOfMonth(1);
            return date;
        }

        LocalDate next(LocalDate start) {
            if (this == YEAR) return start.plusYears(1);
            if (this == MONTH) return start.plusMonths(1);
            return start.plusDays(1);
        }
    }

    private static String normalizeHost(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static void validateColumns(List<String> sourceCols, List<String> targetCols) {
        if (sourceCols == null || targetCols == null || sourceCols.isEmpty() || sourceCols.size() != targetCols.size()) {
            throw new IllegalArgumentException("源与目标字段映射无效");
        }
        for (List<String> names : java.util.Arrays.asList(sourceCols, targetCols)) {
            Set<String> seen = new HashSet<>();
            for (String name : names) {
                quoteIdentifier(name);
                if (!seen.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("字段映射包含重复字段");
            }
        }
    }

    private static String joinColumns(List<String> columns) {
        List<String> quoted = new ArrayList<>();
        for (String column : columns) quoted.add(quoteIdentifier(column));
        return String.join(", ", quoted);
    }

    static String quoteIdentifier(String value) {
        if (value == null || value.isEmpty() || value.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("库、表或字段名称无效");
        }
        return "`" + value.replace("`", "``") + "`";
    }

    static String property(String value) {
        if (value == null) value = "";
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("源连接配置含无效字符");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static SQLException safeSqlException(String operation, SQLException original) {
        if (original instanceof DataSyncCancellation.SyncStoppedException) return new DataSyncCancellation.SyncStoppedException();
        // Deliberately do not retain message, cause, nextException or suppressed driver exceptions.
        return new ImportSQLException(operation + "（SQLState=" + original.getSQLState()
                + ", errorCode=" + original.getErrorCode() + "）", original);
    }

    /** Validation messages are ours; close failures attached by JDBC resources are not. */
    static RuntimeException safeValidationException(RuntimeException failure) {
        RuntimeException safe;
        if (failure instanceof UnsupportedImportException) safe = new UnsupportedImportException(failure.getMessage());
        else if (failure instanceof IllegalArgumentException) safe = new IllegalArgumentException(failure.getMessage());
        else if (failure instanceof IllegalStateException) safe = new IllegalStateException(failure.getMessage());
        else throw new IllegalArgumentException("Unsupported validation exception type");
        for (Throwable suppressed : failure.getSuppressed()) {
            // Keep the owned-table cleanup hint, but never retain a driver's exception object or chain.
            if (suppressed instanceof ImportSQLException) {
                safe.addSuppressed(new ImportSQLException(suppressed.getMessage(), (SQLException) suppressed));
            }
        }
        return safe;
    }

    private static final class ImportSQLException extends SQLException {
        ImportSQLException(String message, SQLException original) {
            super(message, original.getSQLState(), original.getErrorCode());
        }
    }

    static final class Endpoint {
        final String host;
        final int port;
        Endpoint(String host, int port) { this.host = host; this.port = port; }
    }

    static final class Column {
        final String dataType;
        final String columnType;
        final int precision;
        final int scale;
        final int datetimePrecision;
        final boolean nullable;
        Column(String dataType, String columnType, int precision, int scale, int datetimePrecision, boolean nullable) {
            this.dataType = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
            this.columnType = columnType == null ? "" : columnType.toLowerCase(Locale.ROOT);
            this.precision = precision;
            this.scale = scale;
            this.datetimePrecision = datetimePrecision;
            this.nullable = nullable;
        }
    }

    static final class Result {
        final long processed;
        final Timestamp maxTs;
        Result(long processed, Timestamp maxTs) { this.processed = processed; this.maxTs = maxTs; }
    }

    static final class UnsupportedImportException extends IllegalArgumentException {
        UnsupportedImportException(String message) { super(message); }
    }
}
