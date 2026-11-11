package cn.easyreport.sync.datasync;

import cn.easyreport.sync.dm.DmJdbcSupport;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.kingbase.KingbaseJdbcSupport;

import java.io.IOException;
import java.io.Reader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-side contract for the StarRocks 3.1.5 JDBC Resource scanner. No target writes occur here. */
final class StarRocksJdbcResourceSource {
    static final long MAX_TEXT_BYTES = 1_048_576L;
    static final String MYSQL_BIT_COMPATIBLE_DRIVER = "cn.easyreport.sync.jdbc.MySqlBitCompatibleDriver";
    static final String MYSQL_RESOURCE_COMPATIBLE_DRIVER = "cn.easyreport.sync.jdbc.MySqlResourceCompatibleDriver";
    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern MYSQL_BIT_TYPE = Pattern.compile("^BIT\\s*\\(\\s*(\\d+)\\s*\\)$");
    private static final Pattern MYSQL_BINARY_TYPE = Pattern.compile("^(?:BINARY|VARBINARY)(?:\\s*\\(\\s*\\d+\\s*\\))?$|^(?:TINY|MEDIUM|LONG)?BLOB$");
    private static final Pattern MYSQL_TIME_TYPE = Pattern.compile("^TIME(?:\\s*\\(\\s*([0-6])\\s*\\))?$");
    private static final Pattern KINGBASE_TIME_TYPE = Pattern.compile("^TIME(?:\\s*\\(\\s*([0-6])\\s*\\))?(?: WITHOUT TIME ZONE)?$");
    private static final Pattern DM_BINARY_TYPE = Pattern.compile("^(?:BINARY|VARBINARY|RAW)(?:\\s*\\(\\s*\\d+\\s*\\))?$");
    private static final Set<String> READ_SESSION_PARAMETERS = new HashSet<>(Arrays.asList(
            "options", "initparams", "timezone", "sessiontimezone", "datestyle", "serveroption", "server_option"));

    private StarRocksJdbcResourceSource() {
    }

    static Plan prepare(ErDatasource datasource, Connection source, String schema, String table,
            List<String> sourceColumns, String timestampField, Map<String, String> resource,
            DataSyncCancellation cancellation) throws Exception {
        stopped(cancellation);
        String type = datasource == null ? "" : value(datasource.getDatasourceType()).toUpperCase(Locale.ROOT);
        boolean mysqlProtocol = isMySqlProtocol(type);
        boolean kingbaseProjection = "KINGBASE".equals(type);
        boolean dmProjection = "DM".equals(type);
        boolean projected = kingbaseProjection || dmProjection;
        if (!Arrays.asList("KINGBASE", "DM", "POSTGRESQL", "ORACLE", "SQLSERVER").contains(type) && !mysqlProtocol) {
            throw invalid("JDBC Resource 导入不支持该源数据库类型");
        }
        if (sourceColumns == null || sourceColumns.isEmpty()) throw invalid("源字段不能为空");
        DatabaseMetaData database = source.getMetaData();
        Map<String, String> parameters = verifyResourceBinding(datasource, database, resource, type);
        boolean resourceV2 = MYSQL_RESOURCE_COMPATIBLE_DRIVER.equals(resource.get("driver_class"));
        boolean bitCompatibleResource = resourceV2 || MYSQL_BIT_COMPATIBLE_DRIVER.equals(resource.get("driver_class"));
        if (mysqlProtocol || Arrays.asList("POSTGRESQL", "ORACLE", "SQLSERVER").contains(type)) {
            StarRocksCatalogSessionSettings.verifyResource(source, datasource, resource, cancellation);
        }
        String actualSchema = identifier(schema);
        // The Oracle extractor passes an unquoted configured schema through after upper-case
        // metadata lookup. Preserve explicitly quoted owners and physical table names.
        if ("ORACLE".equals(type) && actualSchema != null && !actualSchema.isEmpty()
                && !(schema.startsWith("\"") && schema.endsWith("\""))) actualSchema = actualSchema.toUpperCase(Locale.ROOT);
        table = identifier(table);
        if (actualSchema == null || actualSchema.isEmpty()) {
            actualSchema = "POSTGRESQL".equals(type) ? "public" : mysqlProtocol || "SQLSERVER".equals(type) ? source.getCatalog()
                    : "KINGBASE".equals(type) ? KingbaseJdbcSupport.currentSchema(source)
                    : "DM".equals(type) ? DmJdbcSupport.currentSchema(source) : oracleDefaultOwner(database);
            if ((actualSchema == null || actualSchema.isEmpty()) && (mysqlProtocol || "SQLSERVER".equals(type))) actualSchema = datasource.getDatabaseName();
        }
        if (actualSchema == null || table == null || (mysqlProtocol
                ? !safeMysqlName(actualSchema) || !safeMysqlName(table)
                : !("ORACLE".equals(type) || "SQLSERVER".equals(type) ? safeMysqlName(actualSchema) && safeMysqlName(table)
                    : SIMPLE_NAME.matcher(actualSchema).matches() && SIMPLE_NAME.matcher(table).matches()))) {
            throw invalid("JDBC Resource 首版仅支持普通模式名和表名");
        }
        if ("SQLSERVER".equals(type)) {
            String configuredCatalog = parameters.get("databasename");
            String catalog = source.getCatalog();
            if ((configuredCatalog != null && !equal(actualSchema, configuredCatalog))
                    || (catalog != null && !catalog.equals(actualSchema))) {
                throw invalid("SQL Server 任务模式字段应为源连接的数据库名；当前外表通道固定读取 dbo 模式");
            }
        }
        // In 3.1.5 the BE uses the raw table property and raw column names for non-MySQL URLs.
        // Exercise that SQL against the source. Kingbase and DM use the quoted derived
        // projection below so its scanner sees safe aliases instead of physical names.
        String qualified = mysqlProtocol ? mysqlQuote(actualSchema) + "." + mysqlQuote(table)
                : "SQLSERVER".equals(type) ? bracket(actualSchema) + ".[dbo]." + bracket(table)
                : projected || "POSTGRESQL".equals(type) || "ORACLE".equals(type) ? quote(actualSchema) + "." + quote(table)
                : actualSchema + "." + table;
        Set<String> names = new HashSet<>();
        List<String> selected = new ArrayList<>();
        for (String column : sourceColumns) {
            if (column == null || (mysqlProtocol ? !safeMysqlName(column) || column.indexOf('`') >= 0
                    : !SIMPLE_NAME.matcher(column).matches()) || !names.add(column.toLowerCase(Locale.ROOT))) {
                throw invalid("StarRocks 3.1.5 JDBC Resource 裸列名无法安全读取字段 " + value(column)
                        + "，首版仅支持无 SQL 片段的普通列名");
            }
            selected.add(mysqlProtocol ? mysqlQuote(column) : projected ? quote(column) : column);
        }
        Map<String, String> nativeTypes = mysqlProtocol
                ? starRocksTypes(source, qualified, cancellation) : Collections.emptyMap();
        String emptyQuery = "SELECT " + String.join(", ", selected) + " FROM " + qualified + " WHERE 1=0";
        List<String> physicalSelections = new ArrayList<>();
        for (String column : sourceColumns) physicalSelections.add("SQLSERVER".equals(type) ? bracket(column) : quote(column));
        String physicalTable = "SQLSERVER".equals(type) || "POSTGRESQL".equals(type) || "ORACLE".equals(type)
                ? qualified : quote(actualSchema) + "." + quote(table);
        List<Column> physicalColumns = mysqlProtocol || projected ? null : readColumns(source, "SELECT " + String.join(", ", physicalSelections)
                + " FROM " + physicalTable + " WHERE 1=0", sourceColumns, type, parameters, nativeTypes, bitCompatibleResource, resourceV2, cancellation);
        List<Column> columns = readColumns(source, emptyQuery, sourceColumns, type, parameters, nativeTypes, bitCompatibleResource, resourceV2, cancellation);
        if (physicalColumns == null) physicalColumns = columns;
        for (int index = 0; index < columns.size(); index++) {
            Column raw = columns.get(index);
            Column physical = physicalColumns.get(index);
            if (!raw.physicalName.equals(physical.physicalName) || raw.jdbcType != physical.jdbcType
                    || !raw.nativeType.equals(physical.nativeType) || !raw.javaClassName.equals(physical.javaClassName)
                    || raw.precision != physical.precision || raw.scale != physical.scale
                    || !equal(raw.physicalTable, physical.physicalTable) || !equal(raw.physicalSchema, physical.physicalSchema)) {
                throw invalid("JDBC Resource 裸标识符解析到的字段或类型与源物理字段不一致：" + raw.name);
            }
        }
        String projectedTableProperty = kingbaseProjection
                ? prepareKingbaseProjection(source, qualified, columns, cancellation)
                : dmProjection ? prepareDmProjection(source, qualified, columns, cancellation) : null;
        String timestamp = null;
        if (timestampField != null && !timestampField.trim().isEmpty()) {
            for (Column column : columns) {
                if (column.name.equalsIgnoreCase(timestampField.trim())) {
                    if (!"DATE".equals(column.externalType) && !"DATETIME".equals(column.externalType) && !column.timestampText) {
                        throw invalid("时间戳字段必须为不带时区的 DATE 或 TIMESTAMP");
                    }
                    timestamp = column.name;
                }
            }
            if (timestamp == null) throw invalid("配置的时间戳字段不在源字段中");
        }
        String filter = timestamp == null ? "" : " WHERE " + sourceColumn(timestamp, type) + " IS NOT NULL";
        StringBuilder count = new StringBuilder("SQLSERVER".equals(type) ? "SELECT COUNT_BIG(*)" : "SELECT COUNT(*)");
        List<Column> texts = new ArrayList<>();
        for (Column column : columns) {
            if (column.externalTextCapacity >= 0) {
                texts.add(column);
                String field = sourceTextExpression(column, type);
                if (mysqlProtocol) {
                    if ("STARROCKS".equals(type)) count.append(", MAX(LENGTH(").append(column.json ? "JSON_STRING(" + field + ")" : field).append("))");
                    else if (column.binaryHex) count.append(", MAX(OCTET_LENGTH(").append(field).append(")) * 2");
                    else count.append(", MAX(OCTET_LENGTH(CAST(").append(field).append(" AS CHAR CHARACTER SET utf8mb4)))");
                } else if ("POSTGRESQL".equals(type)) {
                    String bytes = "OCTET_LENGTH(CONVERT_TO(CAST(" + field + " AS TEXT), 'UTF8'))";
                    if (column.jdbcType == Types.CHAR || column.nativeType.startsWith("BPCHAR")) {
                        // PostgreSQL bpchar -> text removes padding, while JDBC getString retains it.
                        bytes += " + OCTET_LENGTH(" + field + ") - OCTET_LENGTH(CAST(" + field + " AS TEXT))";
                    }
                    count.append(", MAX(").append(bytes).append(")");
                } else if (dmProjection && column.binaryHex) {
                    // HEX contains only ASCII; its character count is its UTF-8 byte count.
                    count.append(", MAX(CAST(LENGTH(").append(field).append(") AS BIGINT))");
                } else if ("SQLSERVER".equals(type)) {
                    count.append(", MAX(CAST(DATALENGTH(CONVERT(NVARCHAR(MAX), ").append(field).append(")) AS BIGINT) * 2)");
                } else if (dmProjection) {
                    // Promote before multiplying so a very large CLOB cannot overflow an INT.
                    count.append(", MAX(CAST(LENGTH(").append(field).append(") AS BIGINT) * 4)");
                } else {
                    // Safe UTF-8 bound even if DM uses another server encoding. Ambiguous values
                    // receive exact source-side streaming validation before an external table exists.
                    count.append(", MAX(LENGTH(").append(field).append(") * 4)");
                }
            }
        }
        List<Column> kingbaseBinaries = new ArrayList<>();
        if (kingbaseProjection) {
            for (Column column : columns) {
                if (!column.binaryHex) continue;
                kingbaseBinaries.add(column);
                String raw = sourceColumn(column.name, type);
                String encoded = sourceTextExpression(column, type);
                // Some compatibility settings collapse empty text to NULL. Preserve the
                // distinction between NULL BYTEA and an existing zero-length byte value.
                count.append(", SUM(CASE WHEN ").append(raw).append(" IS NOT NULL AND (")
                        .append(encoded).append(" IS NULL OR LENGTH(").append(encoded)
                        .append(") <> CAST(OCTET_LENGTH(").append(raw)
                        .append(") AS BIGINT) * 2) THEN 1 ELSE 0 END)");
            }
        }
        count.append(" FROM ").append(qualified).append(filter);
        long sourceRowCount;
        List<Column> ambiguousTexts = new ArrayList<>();
        stopped(cancellation);
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(count.toString())) {
            if (!rows.next()) throw invalid("源准确行数查询未返回结果");
            sourceRowCount = rows.getLong(1);
            if (rows.wasNull() || sourceRowCount < 0) throw invalid("源准确行数无效");
            for (int index = 0; index < texts.size(); index++) {
                Column column = texts.get(index);
                long bytes = rows.getLong(index + 2);
                if (rows.wasNull()) bytes = 0;
                boolean exactUtf8 = mysqlProtocol || "POSTGRESQL".equals(type) || dmProjection && column.binaryHex;
                if (bytes < 0 || bytes > (exactUtf8 ? MAX_TEXT_BYTES : MAX_TEXT_BYTES * 4)) {
                    throw invalid("源文本字段 " + column.name + (exactUtf8 ? " 的实际 UTF-8 字节数" : " 的最大字符数")
                            + "超过当前导入通道容量（1 MiB）");
                }
                column.maxTextBytes = bytes;
                if (!exactUtf8 && bytes > column.externalTextCapacity) ambiguousTexts.add(column);
            }
            for (int index = 0; index < kingbaseBinaries.size(); index++) {
                long invalidValues = rows.getLong(2 + texts.size() + index);
                if (!rows.wasNull() && invalidValues != 0) {
                    throw invalid("金仓 BYTEA 字段 " + kingbaseBinaries.get(index).name
                            + " 的 HEX 转换未保留空字节值或原始字节长度，请检查源兼容模式");
                }
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("读取 JDBC Resource 源准确行数及文本容量失败", failure);
        }
        for (Column column : ambiguousTexts) {
            verifyAmbiguousText(source, qualified, filter, column, type, cancellation);
        }
        if (dmProjection) verifyDmTextValues(source, qualified, filter, columns, cancellation);
        Timestamp min = null;
        Timestamp max = null;
        String minText = null;
        String maxText = null;
        if (timestamp != null && sourceRowCount > 0) {
            String field = sourceColumn(timestamp, type);
            if (dmProjection) {
                for (Column column : columns) {
                    if (column.name.equals(timestamp) && column.timestampText) field = "CAST(" + field + " AS TIMESTAMP)";
                }
            }
            String rangeSql = "SELECT MIN(" + field + "), MAX(" + field + ")";
            if ("ORACLE".equals(type)) {
                rangeSql += ", TO_CHAR(CAST(MIN(" + field + ") AS TIMESTAMP), 'YYYY-MM-DD HH24:MI:SS.FF6')"
                        + ", TO_CHAR(CAST(MAX(" + field + ") AS TIMESTAMP), 'YYYY-MM-DD HH24:MI:SS.FF6')";
            }
            stopped(cancellation);
            try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(rangeSql + " FROM " + qualified + filter)) {
                if (!rows.next()) throw invalid("源时间戳范围查询未返回结果");
                min = rows.getTimestamp(1);
                max = rows.getTimestamp(2);
                minText = rows.getString("ORACLE".equals(type) ? 3 : 1);
                maxText = rows.getString("ORACLE".equals(type) ? 4 : 2);
                if (min == null || max == null || min.after(max)) throw invalid("源时间戳范围无效");
            } catch (SQLException failure) {
                stopped(cancellation);
                throw safe("读取 JDBC Resource 源时间戳范围失败", failure);
            }
        }
        if (mysqlProtocol && !"STARROCKS".equals(type) && sourceRowCount > 0) {
            for (Column column : columns) {
                if (!column.mysqlTimestamp) continue;
                Timestamp first = min;
                Timestamp last = max;
                String firstText = minText;
                String lastText = maxText;
                if (!column.name.equals(timestamp)) {
                    String field = mysqlQuote(column.name);
                    String timestampFilter = filter + (filter.isEmpty() ? " WHERE " : " AND ") + field + " IS NOT NULL";
                    stopped(cancellation);
                    try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                            "SELECT MIN(" + field + "), MAX(" + field + ") FROM " + qualified + timestampFilter)) {
                        if (!rows.next()) throw invalid("无法读取源 TIMESTAMP 数据日期范围");
                        first = rows.getTimestamp(1);
                        last = rows.getTimestamp(2);
                        firstText = rows.getString(1);
                        lastText = rows.getString(2);
                    } catch (SQLException failure) {
                        stopped(cancellation);
                        throw safe("读取 JDBC Resource TIMESTAMP 日期范围失败", failure);
                    }
                }
                if ((first == null) != (last == null)) throw invalid("源 TIMESTAMP 数据日期范围不完整");
                column.mysqlTimestampMinText = firstText;
                column.mysqlTimestampMaxText = lastText;
                if (first != null) StarRocksCatalogSessionSettings.verifyResourceTimestampSession(source, datasource,
                        resource, cancellation, wallDay(firstText), wallDay(lastText));
            }
        }
        for (Column column : columns) {
            if (!column.tinyIntBoolean || sourceRowCount == 0) continue;
            String field = sourceColumn(column.name, type);
            String booleanFilter = filter + (filter.isEmpty() ? " WHERE " : " AND ") + field + " NOT IN (0, 1)";
            stopped(cancellation);
            try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM " + qualified + booleanFilter)) {
                if (!rows.next()) throw invalid("无法校验 TINYINT 布尔读取语义");
                long invalidValues = rows.getLong(1);
                if (rows.wasNull() || invalidValues != 0) {
                    throw invalid("源 TINYINT 字段 " + column.name + " 包含无法用 Boolean 保留的值；请在源及 Resource 禁用 tinyInt1isBit");
                }
            } catch (SQLException failure) {
                stopped(cancellation);
                throw safe("校验 JDBC Resource TINYINT 布尔读取语义失败", failure);
            }
        }
        stopped(cancellation);
        String property = projected ? projectedTableProperty
                : mysqlProtocol ? actualSchema.replace("`", "``") + "`.`" + table.replace("`", "``") : qualified;
        String scanTimestamp = timestamp;
        if (projected && timestamp != null) {
            for (Column column : columns) if (column.name.equals(timestamp)) scanTimestamp = column.scanName;
        }
        return new Plan(type, actualSchema, table, property, columns, scanTimestamp, sourceRowCount, min, max,
                min == null ? null : wallDay(minText), max == null ? null : wallDay(maxText));
    }

    private static void verifyAmbiguousText(Connection source, String table, String filter, Column column, String type,
            DataSyncCancellation cancellation) throws SQLException {
        String field = sourceTextExpression(column, type);
        String candidate = "SQLSERVER".equals(type)
                ? "DATALENGTH(CONVERT(NVARCHAR(MAX), " + field + ")) > " + (MAX_TEXT_BYTES / 2)
                : "LENGTH(" + field + ") > " + (MAX_TEXT_BYTES / 4);
        String sql = "SELECT " + field + " FROM " + table + filter + (filter.isEmpty() ? " WHERE " : " AND ") + candidate;
        stopped(cancellation);
        try (Statement statement = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(128);
            try (ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    stopped(cancellation);
                    String text = rows.getString(1);
                    stopped(cancellation);
                    if (text != null && (text.length() > MAX_TEXT_BYTES
                            || text.getBytes(StandardCharsets.UTF_8).length > column.externalTextCapacity)) {
                        throw invalid("源文本字段 " + column.name + " 的实际 UTF-8 字节数超出当前导入通道容量（1 MiB）");
                    }
                }
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("校验 JDBC Resource 源大文本实际 UTF-8 容量失败", failure);
        }
        stopped(cancellation);
        // Smaller, unscanned rows have a four-byte upper bound <= 1 MiB. The BE later obtains
        // exact UTF-8 byte maxima for destination capacity, so this remains a safe upper bound.
        column.maxTextBytes = MAX_TEXT_BYTES;
    }

    /**
     * The 3.1.5 non-MySQL scanner inserts the table property directly into FROM.
     * A read-only derived table lets the existing Kingbase driver return String
     * for JSON/BYTEA and avoids bare reserved words or folded physical names.
     */
    private static String prepareKingbaseProjection(Connection source, String table, List<Column> columns,
            DataSyncCancellation cancellation) throws SQLException {
        List<String> selections = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            if (!column.name.equals(column.physicalName)) {
                throw invalid("金仓源字段 " + column.name + " 的物理列名与配置不一致");
            }
            column.scanName = "er_c" + index;
            aliases.add(column.scanName);
            selections.add(sourceTextExpression(column, "KINGBASE") + " AS " + column.scanName);
        }
        String derived = "(SELECT " + String.join(", ", selections) + " FROM " + table + ") er_source";
        stopped(cancellation);
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + String.join(", ", aliases) + " FROM " + derived + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata.getColumnCount() != columns.size()) throw invalid("金仓 Resource 投影字段数量与源表不一致");
            for (int index = 0; index < columns.size(); index++) {
                stopped(cancellation);
                Column column = columns.get(index);
                int jdbcIndex = index + 1;
                // Unquoted aliases and scanner references undergo the same server folding.
                if (!column.scanName.equalsIgnoreCase(metadata.getColumnLabel(jdbcIndex))) {
                    throw invalid("金仓 Resource 字段 " + column.name + " 的安全别名未正确解析");
                }
                if (column.json || column.binaryHex || column.timeText) {
                    int jdbcType = metadata.getColumnType(jdbcIndex);
                    if (!"java.lang.String".equals(metadata.getColumnClassName(jdbcIndex))
                            || !Arrays.asList(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(jdbcType)) {
                        throw invalid("金仓字段 " + column.name + " 转为文本后未以 String 返回，无法使用现有 Resource 驱动读取");
                    }
                } else if (metadata.getColumnType(jdbcIndex) != column.jdbcType
                        || !column.javaClassName.equals(metadata.getColumnClassName(jdbcIndex))
                        || metadata.getPrecision(jdbcIndex) != column.precision
                        || metadata.getScale(jdbcIndex) != column.scale) {
                    throw invalid("金仓 Resource 字段 " + column.name + " 的投影改变了源标量类型或精度");
                }
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("校验金仓 JDBC Resource 文本转换及字段别名失败", failure);
        }
        return derived;
    }

    /** Keep the existing DM driver, but expose only names and Java types the BE scanner can read. */
    private static String prepareDmProjection(Connection source, String table, List<Column> columns,
            DataSyncCancellation cancellation) throws SQLException {
        List<String> selections = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            if (!column.name.equals(column.physicalName)) throw invalid("达梦源字段 " + column.name + " 的物理列名与配置不一致");
            column.scanName = "er_c" + index;
            aliases.add(column.scanName);
            selections.add(sourceTextExpression(column, "DM") + " AS " + column.scanName);
        }
        String derived = "(SELECT " + String.join(", ", selections) + " FROM " + table + ") er_source";
        stopped(cancellation);
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + String.join(", ", aliases) + " FROM " + derived + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata.getColumnCount() != columns.size()) throw invalid("达梦 Resource 投影字段数量与源表不一致");
            for (int index = 0; index < columns.size(); index++) {
                stopped(cancellation);
                Column column = columns.get(index);
                int jdbcIndex = index + 1;
                if (!column.scanName.equalsIgnoreCase(metadata.getColumnLabel(jdbcIndex))) {
                    throw invalid("达梦 Resource 字段 " + column.name + " 的安全别名未正确解析");
                }
                if (column.binaryHex || column.timestampText) {
                    if (!"java.lang.String".equals(metadata.getColumnClassName(jdbcIndex))
                            || !Arrays.asList(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(metadata.getColumnType(jdbcIndex))) {
                        throw invalid("达梦字段 " + column.name + " 转为文本后未以 String 返回，无法使用现有 Resource 驱动读取");
                    }
                } else if (metadata.getColumnType(jdbcIndex) != column.jdbcType
                        || !column.javaClassName.equals(metadata.getColumnClassName(jdbcIndex))
                        || metadata.getPrecision(jdbcIndex) != column.precision
                        || metadata.getScale(jdbcIndex) != column.scale) {
                    throw invalid("达梦 Resource 字段 " + column.name + " 的投影改变了源标量类型或精度");
                }
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("校验达梦 JDBC Resource 文本转换及字段别名失败", failure);
        }
        return derived;
    }

    /**
     * RAWTOHEX must preserve every byte, and CLOB getString must agree with its full character stream.
     * Do not use CAST(CLOB AS VARCHAR) or a fixed SUBSTR: either can silently truncate large values.
     */
    private static void verifyDmTextValues(Connection source, String table, String filter, List<Column> columns,
            DataSyncCancellation cancellation) throws SQLException {
        for (Column column : columns) {
            boolean clob = isDmClob(column.nativeType);
            if (!column.binaryHex && !clob) continue;
            String field = sourceColumn(column.name, "DM");
            String sql = "SELECT " + field + (column.binaryHex ? ", " + sourceTextExpression(column, "DM") : "")
                    + " FROM " + table + filter;
            stopped(cancellation);
            try (Statement statement = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(128);
                try (ResultSet rows = statement.executeQuery(sql)) {
                    long maximum = 0;
                    while (rows.next()) {
                        stopped(cancellation);
                        String text;
                        if (column.binaryHex) {
                            byte[] raw = rows.getBytes(1);
                            text = rows.getString(2);
                            if (raw != null && raw.length > MAX_TEXT_BYTES / 2) throw invalid("达梦二进制字段 " + column.name + " 转为 HEX 后超过导入容量（1 MiB）");
                            if (!equal(raw == null ? null : hex(raw), text)) {
                                throw invalid("达梦二进制字段 " + column.name + " 的 HEX 转换未完整保留原始字节、空值或空字节串");
                            }
                        } else {
                            text = rows.getString(1);
                            if (text != null && text.length() > MAX_TEXT_BYTES) throw invalid("达梦 CLOB 字段 " + column.name + " 超过导入容量（1 MiB）");
                            try (Reader reader = rows.getCharacterStream(1)) {
                                if ((text == null) != (reader == null)) throw invalid("达梦 CLOB 字段 " + column.name + " 的字符串读取未保留 NULL");
                                if (reader != null) {
                                    char[] buffer = new char[4096];
                                    int offset = 0;
                                    int length;
                                    while ((length = reader.read(buffer)) != -1) {
                                        stopped(cancellation);
                                        if (length == 0) continue;
                                        if (offset + length > text.length()) throw invalid("达梦 CLOB 字段 " + column.name + " 的驱动字符串读取发生截断");
                                        for (int i = 0; i < length; i++) {
                                            if (buffer[i] != text.charAt(offset + i)) throw invalid("达梦 CLOB 字段 " + column.name + " 的驱动字符串读取与完整字符流不一致");
                                        }
                                        offset += length;
                                    }
                                    if (offset != text.length()) throw invalid("达梦 CLOB 字段 " + column.name + " 的驱动字符串读取与完整字符流长度不一致");
                                }
                            }
                        }
                        if (text != null) {
                            long bytes = text.getBytes(StandardCharsets.UTF_8).length;
                            if (bytes > MAX_TEXT_BYTES) throw invalid("达梦文本字段 " + column.name + " 的实际 UTF-8 字节数超过导入容量（1 MiB）");
                            maximum = Math.max(maximum, bytes);
                        }
                    }
                    column.maxTextBytes = maximum;
                }
            } catch (IOException failure) {
                stopped(cancellation);
                throw invalid("读取达梦 CLOB 字段 " + column.name + " 的完整字符流失败");
            } catch (SQLException failure) {
                stopped(cancellation);
                throw safe("校验达梦 JDBC Resource 二进制或 CLOB 完整读取失败", failure);
            }
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] encoded = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int unsigned = bytes[index] & 0xff;
            encoded[index * 2] = digits[unsigned >>> 4];
            encoded[index * 2 + 1] = digits[unsigned & 0xf];
        }
        return new String(encoded);
    }

    private static boolean isDmClob(String nativeType) {
        return Arrays.asList("CLOB", "NCLOB", "TEXT").contains(nativeType);
    }

    private static String sourceTextExpression(Column column, String sourceType) {
        String field = sourceColumn(column.name, sourceType);
        if ("KINGBASE".equals(sourceType)) {
            if (column.json || column.timeText) return "CAST(" + field + " AS TEXT)";
            if (column.binaryHex) return "UPPER(ENCODE(" + field + ", 'hex'))";
        }
        if ("DM".equals(sourceType)) {
            if (column.binaryHex) return "UPPER(RAWTOHEX(" + field + "))";
            if (column.timestampText) {
                String timestamp = "CAST(" + field + " AS TIMESTAMP)";
                // YYYY omits the era. Reject BC values instead of silently turning them into AD;
                // the invalid marker is rejected by the target's guarded DATETIME conversion.
                return "CASE WHEN " + timestamp + " < TIMESTAMP '0001-01-01 00:00:00'"
                        + " OR " + timestamp + " > TIMESTAMP '9999-12-31 23:59:59.999999'"
                        + " THEN 'invalid datetime' ELSE TO_CHAR(" + timestamp
                        + ", 'YYYY-MM-DD HH24:MI:SS.FF6') END";
            }
        }
        return field;
    }

    private static List<Column> readColumns(Connection source, String sql, List<String> sourceColumns,
            String sourceType, Map<String, String> parameters, Map<String, String> nativeTypes,
            boolean bitCompatibleResource, boolean resourceV2, DataSyncCancellation cancellation) throws SQLException {
        List<Column> columns = new ArrayList<>();
        stopped(cancellation);
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata.getColumnCount() != sourceColumns.size()) throw invalid("源字段元数据数量与配置不一致");
            for (int index = 0; index < sourceColumns.size(); index++) {
                stopped(cancellation);
                String nativeType = nativeTypes.get(sourceColumns.get(index).toLowerCase(Locale.ROOT));
                if (isMySqlProtocol(sourceType) && nativeType == null) throw invalid("DESCRIBE 缺少源字段 " + sourceColumns.get(index));
                columns.add(column(metadata, index + 1, sourceColumns.get(index), sourceType, parameters, nativeType, bitCompatibleResource, resourceV2));
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("读取 JDBC Resource 源字段类型失败", failure);
        }
        return columns;
    }

    private static Column column(ResultSetMetaData metadata, int index, String name, String sourceType,
            Map<String, String> parameters, String describedType, boolean bitCompatibleResource, boolean resourceV2) throws SQLException {
        int jdbcType = metadata.getColumnType(index);
        String nativeType = value(metadata.getColumnTypeName(index)).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (describedType != null) nativeType = describedType.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if ("SQLSERVER".equals(sourceType) && "TINYINT".equals(nativeType)) nativeType = "TINYINT UNSIGNED";
        if ("STARROCKS".equals(sourceType) && nativeType.matches("TINYINT\\s*\\(\\s*1\\s*\\)")) nativeType = "BOOLEAN";
        String javaClass = value(metadata.getColumnClassName(index));
        int precision = metadata.getPrecision(index);
        int scale = metadata.getScale(index);
        boolean mysql = "MYSQL".equals(sourceType) || "TIDB".equals(sourceType);
        boolean kingbaseBinary = "KINGBASE".equals(sourceType) && "BYTEA".equals(nativeType);
        boolean dmBinary = "DM".equals(sourceType) && DM_BINARY_TYPE.matcher(nativeType).matches();
        boolean dmClob = "DM".equals(sourceType) && isDmClob(nativeType);
        boolean dmDate = "DM".equals(sourceType) && "DATE".equals(nativeType);
        // The Resource may use the same driver with different convertToTimestamp defaults.
        // Format all DM timestamps in SQL so the BE always receives String on both connections.
        boolean timestampText = "DM".equals(sourceType) && (dmDate || jdbcType == Types.TIMESTAMP);
        boolean binaryHex = kingbaseBinary || dmBinary || mysql && MYSQL_BINARY_TYPE.matcher(nativeType).matches();
        boolean kingbaseTime = "KINGBASE".equals(sourceType) && KINGBASE_TIME_TYPE.matcher(nativeType).matches();
        boolean timeText = kingbaseTime || mysql && MYSQL_TIME_TYPE.matcher(nativeType).matches();
        if (binaryHex) {
            if (!kingbaseBinary && !dmBinary) requireCompatibleV2(resourceV2, name, "二进制/BLOB");
            if (!Arrays.asList(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB).contains(jdbcType)
                    || !(kingbaseBinary || dmBinary ? "[B".equals(javaClass)
                        : Arrays.asList("[B", "java.sql.Blob", "com.mysql.cj.jdbc.Blob", "com.mysql.jdbc.Blob").contains(javaClass))) {
                throw invalid("源二进制/BLOB 字段 " + name + " 的 JDBC 类型或驱动返回类尚未验证可无损转换为 HEX 文本");
            }
        }
        if (timeText) {
            if (!kingbaseTime) requireCompatibleV2(resourceV2, name, "TIME");
            if (jdbcType != Types.TIME || !Arrays.asList("java.sql.Time", "java.lang.String", "java.time.LocalTime").contains(javaClass)
                    || scale < 0 || scale > 6) {
                throw invalid("源 TIME 字段 " + name + " 的 JDBC 类型、精度或驱动返回类尚未验证可保留原始文本");
            }
        }
        if (dmClob && (!Arrays.asList(Types.CLOB, Types.NCLOB, Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(jdbcType)
                || !Arrays.asList("dm.jdbc.driver.DmdbClob", "dm.jdbc.driver.DmdbNClob", "java.sql.Clob",
                        "java.sql.NClob", "java.lang.String").contains(javaClass))) {
            throw invalid("达梦 CLOB 字段 " + name + " 的 JDBC 类型或驱动返回类不受当前文本通道支持");
        }
        if (timestampText && ((!dmDate && jdbcType != Types.TIMESTAMP)
                || (dmDate && !Arrays.asList(Types.DATE, Types.TIMESTAMP).contains(jdbcType))
                || !(dmDate && "java.sql.Date".equals(javaClass)
                        || Arrays.asList("java.sql.Timestamp", "dm.jdbc.driver.DmdbTimestamp", "java.time.LocalDateTime").contains(javaClass))
                || scale < 0 || scale > 6)) {
            throw invalid("达梦日期时间字段 " + name + " 的 JDBC 类型、精度或驱动返回类无法保证无损读取");
        }
        int unsignedBitWidth = 0;
        if (mysql
                && (nativeType.equals("BIT") || nativeType.startsWith("BIT(") || nativeType.startsWith("BIT "))) {
            int bitWidth = mysqlBitWidth(nativeType, name);
            if (jdbcType != Types.BIT || scale != 0) throw invalid("源 BIT 字段 " + name + " 的 JDBC 类型或小数位与原生类型不一致");
            // DESCRIBE carries the actual bit width. Connector/J may advertise Boolean and
            // a different precision even though getObject() returns byte[] for BIT(n > 1).
            precision = bitWidth;
            if (bitWidth > 1) {
                requireCompatibleV2(bitCompatibleResource, name, "多位 BIT");
                if (!Arrays.asList("java.lang.Boolean", "[B", "java.math.BigInteger").contains(javaClass)) {
                    throw invalid("源多位 BIT 字段 " + name + " 的驱动返回类尚未验证可无损转换为无符号整数");
                }
                unsignedBitWidth = bitWidth;
            }
        }
        int nullability = metadata.isNullable(index);
        String physicalName = metadata.getColumnName(index);
        if (physicalName == null || physicalName.isEmpty()) throw invalid("源字段 " + name + " 未返回物理列名");
        boolean kingbaseJson = "KINGBASE".equals(sourceType) && ("JSON".equals(nativeType) || "JSONB".equals(nativeType));
        boolean json = kingbaseJson || isMySqlProtocol(sourceType) && "JSON".equals(nativeType);
        boolean largeInteger = "STARROCKS".equals(sourceType) && nativeType.matches("LARGEINT(?:\\(\\d+\\))?");
        Matcher nativeDecimal = Pattern.compile("^DECIMAL(?:32|64|128)?\\((\\d+),\\s*(\\d+)\\)$").matcher(nativeType);
        if ("STARROCKS".equals(sourceType) && nativeDecimal.matches()) {
            precision = Integer.parseInt(nativeDecimal.group(1)); scale = Integer.parseInt(nativeDecimal.group(2));
            nativeType = "DECIMAL(" + precision + "," + scale + ")";
        }
        if (nativeType.isEmpty() || nativeType.matches(".*WITH(?: LOCAL)? TIME ZONE.*") || nativeType.contains("TIMESTAMPTZ")
                || "DATETIMEOFFSET".equals(nativeType)
                || nativeType.endsWith("_TZ") || (!isScalarNativeType(nativeType) && !json && !largeInteger && !binaryHex && !timeText && !dmClob)
                || (jdbcType == Types.CLOB || jdbcType == Types.NCLOB) && !dmClob || (jdbcType == Types.BLOB && !binaryHex) || jdbcType == Types.ARRAY
                || jdbcType == Types.STRUCT || jdbcType == Types.SQLXML || (jdbcType == Types.OTHER && !json)) {
            throw invalid("源字段 " + name + " 类型 " + nativeType + " 尚未验证可通过 JDBC Resource 无损导入");
        }
        String externalType;
        long textCapacity = -1;
        boolean largeIntegerText = false;
        if (timestampText) {
            // Both scanner implementations know String, whereas the vendor DmdbTimestamp class
            // follows the scanner's VARCHAR fallback. Explicit source formatting preserves clocks
            // independently of dateToTimestamp / convertToTimestamp connection settings.
            externalType = "VARCHAR(26)";
        } else if (dmClob) {
            // JDBCScanner obtains getString for vendor CLOB classes. Validate against the character
            // stream below; never reduce the source value to a bounded DM VARCHAR expression.
            externalType = "VARCHAR(" + MAX_TEXT_BYTES + ")";
            textCapacity = MAX_TEXT_BYTES;
        } else if (unsignedBitWidth > 0) {
            externalType = "LARGEINT";
        } else if (binaryHex || timeText) {
            // Kingbase and DM convert binary in the derived query; MySQL uses the v2 driver.
            externalType = "VARCHAR(" + MAX_TEXT_BYTES + ")";
            textCapacity = MAX_TEXT_BYTES;
        } else if (json) {
            if (!"java.lang.String".equals(javaClass)
                    && !(kingbaseJson && Arrays.asList("com.kingbase8.util.KBobject", "java.lang.Object").contains(javaClass))) {
                throw invalid("源 JSON 字段 " + name + " 未以经过验证的 String 或 JSON 对象返回");
            }
            externalType = "VARCHAR(" + MAX_TEXT_BYTES + ")";
            textCapacity = MAX_TEXT_BYTES;
        } else if (largeInteger && "java.lang.String".equals(javaClass)) {
            externalType = "VARCHAR(40)";
            largeIntegerText = true;
        } else switch (javaClass) {
            case "java.lang.Byte":
                if (jdbcType != Types.TINYINT) throw invalid("Byte 字段 " + name + " 的 JDBC 类型不受支持");
                // 3.1.5 treats Byte as an unknown JDBC class and obtains getString(). Its standard
                // signed decimal representation is bounded to four characters and remains numeric.
                externalType = "VARCHAR(4)"; break;
            case "java.lang.Boolean":
                if (jdbcType == Types.BIT && precision > 1) throw invalid("多位 BIT 字段 " + name + " 不能当布尔值导入");
                externalType = "BOOLEAN"; break;
            case "java.lang.Short": externalType = "SMALLINT"; break;
            case "java.lang.Integer": externalType = "INT"; break;
            case "java.lang.Long": externalType = "BIGINT"; break;
            case "java.math.BigInteger":
                if (!largeInteger && (precision < 1 || precision > 38)) throw invalid("大整数字段 " + name + " 的范围无法安全确认");
                externalType = "LARGEINT"; break;
            case "java.math.BigDecimal":
                if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
                    throw invalid("NUMERIC/NUMBER 字段 " + name + " 需要已知且不超过 38 位的精度及有效小数位");
                }
                externalType = "DECIMAL(" + precision + "," + scale + ")"; break;
            case "java.lang.Float": externalType = "FLOAT"; break;
            case "java.lang.Double": externalType = "DOUBLE"; break;
            case "java.lang.String":
                if (jdbcType != Types.CHAR && jdbcType != Types.VARCHAR && jdbcType != Types.NCHAR
                        && jdbcType != Types.NVARCHAR && jdbcType != Types.LONGVARCHAR && jdbcType != Types.LONGNVARCHAR) {
                    throw invalid("源字段 " + name + " 的 String 返回值不能证明原生类型可无损读取");
                }
                externalType = "VARCHAR(" + MAX_TEXT_BYTES + ")";
                textCapacity = MAX_TEXT_BYTES;
                break;
            case "java.sql.Date":
                if (jdbcType != Types.DATE) throw invalid("源日期字段 " + name + " 的 JDBC 类型与 Java 返回类型不一致");
                if (("DM".equals(sourceType) || "ORACLE".equals(sourceType)) && "DATE".equals(nativeType)) {
                    throw invalid(("DM".equals(sourceType) ? "达梦" : "Oracle") + " DATE 字段 " + name
                            + " 可能包含时分秒，需在源和 Resource 一致配置 "
                            + ("DM".equals(sourceType) ? "dateToTimestamp=true" : "oracle.jdbc.mapDateToTimestamp=true")
                            + " 并确认驱动返回类型");
                }
                externalType = "DATE"; break;
            case "java.sql.Timestamp":
            case "dm.jdbc.driver.DmdbTimestamp":
                if ("dm.jdbc.driver.DmdbTimestamp".equals(javaClass) && !"DM".equals(sourceType)) {
                    throw invalid("源字段 " + name + " 的驱动返回类与数据库类型不一致");
                }
                boolean oracleDate = "ORACLE".equals(sourceType) && "DATE".equals(nativeType) && jdbcType == Types.DATE;
                if ((!oracleDate && jdbcType != Types.TIMESTAMP) || scale < 0 || scale > 6) {
                    throw invalid("时间戳字段 " + name + " 类型或精度不受当前通道支持");
                }
                externalType = "DATETIME"; break;
            case "java.time.LocalDateTime":
                if (jdbcType != Types.TIMESTAMP || scale != 0) {
                    throw invalid("StarRocks 3.1.5 会截断 LocalDateTime 小数秒，字段 " + name + " 不能无损导入");
                }
                externalType = "DATETIME"; break;
            default:
                throw invalid("源字段 " + name + " 的驱动返回类 " + javaClass + " 尚未验证，不降级为文本导入");
        }
        Column column = new Column(name, physicalName, metadata.getTableName(index), metadata.getSchemaName(index),
                jdbcType, nativeType, javaClass, externalType,
                nullability != ResultSetMetaData.columnNoNulls, precision, scale, textCapacity, json, largeIntegerText,
                ("MYSQL".equals(sourceType) || "TIDB".equals(sourceType)) && nativeType.startsWith("TIMESTAMP"),
                isMySqlProtocol(sourceType) && "java.lang.Boolean".equals(javaClass) && nativeType.startsWith("TINYINT"), unsignedBitWidth,
                binaryHex, timeText);
        column.timestampText = timestampText;
        return column;
    }

    private static void requireCompatibleV2(boolean compatibleResource, String name, String kind) {
        if (!compatibleResource) throw invalid("源 " + kind + " 字段 " + name
                + " 需要部署 MySQL 兼容驱动 v2 JAR 至 StarRocks FE/BE，并在 JDBC Resource 配置 "
                + MYSQL_RESOURCE_COMPATIBLE_DRIVER + "；仅更新应用后端不能修复此类型");
    }

    private static int mysqlBitWidth(String nativeType, String name) {
        if ("BIT".equals(nativeType)) return 1; // MySQL's BIT default is one bit.
        Matcher matcher = MYSQL_BIT_TYPE.matcher(nativeType);
        if (matcher.matches()) {
            try {
                int width = Integer.parseInt(matcher.group(1));
                if (width >= 1 && width <= 64) return width;
            } catch (NumberFormatException invalidWidth) {
                // A malformed native width must not fall back to driver Boolean metadata.
            }
        }
        throw invalid("源字段 " + name + " 的原生 BIT 位宽无效（须为 1 至 64）");
    }

    private static boolean isScalarNativeType(String type) {
        String base = type.replaceAll("\\b(?:UNSIGNED|ZEROFILL)\\b", "").replaceFirst("\\s*\\(.*$", "").trim();
        return Arrays.asList("BYTE", "TINYINT", "SMALLINT", "MEDIUMINT", "INTEGER", "INT", "BIGINT", "INT1", "INT2", "INT3", "INT4", "INT8",
                "SMALLSERIAL", "SERIAL", "BIGSERIAL", "NUMBER", "NUMERIC", "DECIMAL", "DEC", "REAL", "FLOAT",
                "FLOAT4", "FLOAT8", "DOUBLE", "DOUBLE PRECISION", "BINARY_FLOAT", "BINARY_DOUBLE", "BOOLEAN",
                "BOOL", "BIT", "CHAR", "NCHAR", "VARCHAR", "NVARCHAR", "VARCHAR2", "NVARCHAR2", "BPCHAR",
                "CHARACTER", "CHARACTER VARYING", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "ENUM", "SET", "STRING", "DATE", "DATETIME", "TIMESTAMP",
                "TIMESTAMP WITHOUT TIME ZONE", "DATETIME2", "SMALLDATETIME", "MONEY", "SMALLMONEY", "UNIQUEIDENTIFIER").contains(base);
    }

    private static Map<String, String> verifyResourceBinding(ErDatasource datasource, DatabaseMetaData metadata,
            Map<String, String> resource, String type) throws SQLException {
        if (resource == null) throw invalid("缺少预建 JDBC Resource 配置");
        if (!(validDriver(type, resource.get("driver_class"))
                || (("MYSQL".equals(type) || "TIDB".equals(type))
                    && (MYSQL_BIT_COMPATIBLE_DRIVER.equals(resource.get("driver_class"))
                        || MYSQL_RESOURCE_COMPATIBLE_DRIVER.equals(resource.get("driver_class")))))
                || (datasource.getDriverClass() != null && !datasource.getDriverClass().isEmpty()
                    && !validDriver(type, datasource.getDriverClass()))) {
            throw invalid("JDBC Resource 驱动类与源数据库类型不一致");
        }
        if (datasource.getUsername() == null || !datasource.getUsername().equals(resource.get("user"))) {
            throw invalid("JDBC Resource 用户与源连接用户不一致，无法确认读取的是相同数据");
        }
        Map<String, String> configured = ConnectionParameters.parse(datasource.getJdbcUrl(), type);
        Map<String, String> linked = ConnectionParameters.parse(resource.get("jdbc_uri"), type);
        Map<String, String> actual;
        try { actual = ConnectionParameters.parse(metadata.getURL(), type); }
        catch (SQLException failure) { throw safe("无法核对实际源 JDBC 连接", failure); }
        // Resource endpoints are user-managed and may use a different network route
        // or default database. The actual BE scan checks connectivity before clearing.
        for (String key : READ_SESSION_PARAMETERS) {
            String left = configured.get(key);
            String right = linked.get(key);
            if (!equal(left, right)) throw invalid("JDBC Resource 与源连接参数 " + key + " 不一致");
        }
        if ("ORACLE".equals(type)) {
            if (oracleDateAsTimestamp(configured) != oracleDateAsTimestamp(actual)) {
                throw invalid("源 Oracle DATE 转换设置与实际连接不一致");
            }
            if (oracleDateAsTimestamp(actual) != oracleDateAsTimestamp(linked)) {
                throw invalid("JDBC Resource 与源 Oracle DATE 转换设置不一致");
            }
        }
        configured.put("resource.datetotimestamp", linked.get("datetotimestamp"));
        return configured;
    }

    private static boolean equal(String left, String right) { return left == null ? right == null : left.equals(right); }
    private static boolean isMySqlProtocol(String type) { return "MYSQL".equals(type) || "TIDB".equals(type) || "STARROCKS".equals(type); }
    private static boolean mysqlDriver(String driver) { return "com.mysql.cj.jdbc.Driver".equals(driver) || "com.mysql.jdbc.Driver".equals(driver); }
    private static boolean validDriver(String type, String driver) {
        if (isMySqlProtocol(type)) return mysqlDriver(driver);
        if ("KINGBASE".equals(type)) return "com.kingbase8.Driver".equals(driver);
        if ("DM".equals(type)) return "dm.jdbc.driver.DmDriver".equals(driver);
        if ("POSTGRESQL".equals(type)) return "org.postgresql.Driver".equals(driver);
        if ("SQLSERVER".equals(type)) return "com.microsoft.sqlserver.jdbc.SQLServerDriver".equals(driver);
        return "ORACLE".equals(type) && ("oracle.jdbc.OracleDriver".equals(driver) || "oracle.jdbc.driver.OracleDriver".equals(driver));
    }
    private static boolean truth(String text) { return "true".equalsIgnoreCase(text) || "1".equals(text); }
    private static boolean oracleDateAsTimestamp(Map<String, String> parameters) {
        String value = parameters.get("oracle.jdbc.mapdatetotimestamp");
        String alias = parameters.get("mapdatetotimestamp");
        if (value != null && alias != null && !value.equals(alias)) throw invalid("Oracle DATE 转换参数冲突");
        String setting = value == null ? alias : value;
        if (setting == null) return true; // Oracle JDBC's documented default.
        if (!"true".equals(setting) && !"false".equals(setting)) throw invalid("Oracle DATE 转换参数值无效");
        return truth(setting);
    }
    private static String value(String value) { return value == null ? "" : value; }
    private static void stopped(DataSyncCancellation cancellation) throws DataSyncCancellation.SyncStoppedException {
        if (cancellation != null) cancellation.checkStopped();
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message + "，尚未清空目标表");
    }
    private static SQLException safe(String message, SQLException failure) {
        if (failure instanceof DataSyncCancellation.SyncStoppedException) return failure;
        return new SQLException(message + "，尚未清空目标表（SQLState=" + failure.getSQLState()
                + ", errorCode=" + failure.getErrorCode() + "）", failure.getSQLState(), failure.getErrorCode());
    }
    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private static String bracket(String name) { return "[" + name.replace("]", "]]") + "]"; }
    private static String mysqlQuote(String name) { return "`" + name.replace("`", "``") + "`"; }
    private static String sourceColumn(String name, String type) {
        if (isMySqlProtocol(type)) return mysqlQuote(name);
        if ("SQLSERVER".equals(type)) return bracket(name);
        return "KINGBASE".equals(type) || "DM".equals(type) || "POSTGRESQL".equals(type) || "ORACLE".equals(type) ? quote(name) : name;
    }
    private static String identifier(String name) {
        if (name != null && name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            return name.substring(1, name.length() - 1).replace("\"\"", "\"");
        }
        return name;
    }
    private static String oracleDefaultOwner(DatabaseMetaData database) throws SQLException {
        // OracleDataSyncExtractor enumerates the login user's owner, independently of
        // ALTER SESSION SET CURRENT_SCHEMA. Use the same owner to avoid a same-name table swap.
        String user = database.getUserName();
        if (user == null || user.isEmpty()) throw invalid("无法确定 Oracle 源连接的登录模式");
        return user.toUpperCase(Locale.ROOT);
    }
    private static boolean safeMysqlName(String name) { return !name.isEmpty() && name.chars().noneMatch(c -> c < 32 || c == 127); }
    private static LocalDate wallDay(String value) {
        try {
            if (value == null || value.length() < 10) throw new IllegalArgumentException();
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException invalidDate) {
            throw invalid("无法从源时间戳原始文本确定自然时间窗口日期");
        }
    }

    private static Map<String, String> starRocksTypes(Connection source, String table, DataSyncCancellation cancellation) throws SQLException {
        Map<String, String> types = new HashMap<>();
        stopped(cancellation);
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery("DESCRIBE " + table)) {
            while (rows.next()) {
                stopped(cancellation);
                String name = rows.getString("Field");
                String type = rows.getString("Type");
                if (name == null || type == null || type.trim().isEmpty()) throw invalid("源原生类型元数据不完整");
                types.put(name.toLowerCase(Locale.ROOT), type);
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("读取源原生字段类型失败", failure);
        }
        return types;
    }

    static final class Plan {
        final String sourceType;
        final String schema;
        final String table;
        final String sourceTableProperty;
        final String externalColumnsSql;
        final String selectColumnsSql;
        final String timestampColumn;
        final long sourceRowCount;
        final Timestamp minTs;
        final Timestamp maxTs;
        final LocalDate minDay;
        final LocalDate maxDay;
        final List<Column> columns;
        final boolean bitNumeric;

        Plan(String sourceType, String schema, String table, String sourceTableProperty, List<Column> columns,
                String timestampColumn, long count, Timestamp min, Timestamp max, LocalDate minDay, LocalDate maxDay) {
            this.sourceType = sourceType;
            this.schema = schema;
            this.table = table;
            this.sourceTableProperty = sourceTableProperty;
            this.columns = Collections.unmodifiableList(columns);
            this.timestampColumn = timestampColumn;
            this.sourceRowCount = count;
            this.minTs = min;
            this.maxTs = max;
            this.minDay = minDay;
            this.maxDay = maxDay;
            List<String> declarations = new ArrayList<>();
            List<String> selections = new ArrayList<>();
            boolean numericBits = false;
            for (Column column : columns) {
                numericBits |= column.unsignedBitWidth > 0;
                String name = "`" + column.scanName + "`";
                // Vendor nullable metadata is not a target constraint. All temporary columns are
                // nullable; the real destination's constraints remain enforced by strict INSERT.
                declarations.add(name + " " + column.externalType + " NULL");
                selections.add(column.largeIntegerText ? "CAST(" + name + " AS LARGEINT)"
                        : column.timestampText ? "CAST(" + name + " AS DATETIME)" : name);
            }
            this.externalColumnsSql = String.join(", ", declarations);
            this.selectColumnsSql = String.join(", ", selections);
            this.bitNumeric = numericBits;
        }
    }

    static final class Column {
        final String name;
        String scanName;
        final String physicalName;
        final String physicalTable;
        final String physicalSchema;
        final int jdbcType;
        final String nativeType;
        final String javaClassName;
        final String externalType;
        final boolean nullable;
        final int precision;
        final int scale;
        final long externalTextCapacity;
        final boolean json;
        final boolean largeIntegerText;
        final boolean mysqlTimestamp;
        final boolean tinyIntBoolean;
        final int unsignedBitWidth;
        final boolean binaryHex;
        final boolean timeText;
        boolean timestampText;
        String mysqlTimestampMinText;
        String mysqlTimestampMaxText;
        long maxTextBytes = -1;

        Column(String name, String physicalName, String physicalTable, String physicalSchema,
                int jdbcType, String nativeType, String javaClassName, String externalType,
                boolean nullable, int precision, int scale, long textCapacity,
                boolean json, boolean largeIntegerText, boolean mysqlTimestamp, boolean tinyIntBoolean) {
            this(name, physicalName, physicalTable, physicalSchema, jdbcType, nativeType, javaClassName, externalType,
                    nullable, precision, scale, textCapacity, json, largeIntegerText, mysqlTimestamp, tinyIntBoolean, 0);
        }

        Column(String name, String physicalName, String physicalTable, String physicalSchema,
                int jdbcType, String nativeType, String javaClassName, String externalType,
                boolean nullable, int precision, int scale, long textCapacity,
                boolean json, boolean largeIntegerText, boolean mysqlTimestamp, boolean tinyIntBoolean, int unsignedBitWidth) {
            this(name, physicalName, physicalTable, physicalSchema, jdbcType, nativeType, javaClassName, externalType,
                    nullable, precision, scale, textCapacity, json, largeIntegerText, mysqlTimestamp, tinyIntBoolean,
                    unsignedBitWidth, false, false);
        }

        Column(String name, String physicalName, String physicalTable, String physicalSchema,
                int jdbcType, String nativeType, String javaClassName, String externalType,
                boolean nullable, int precision, int scale, long textCapacity,
                boolean json, boolean largeIntegerText, boolean mysqlTimestamp, boolean tinyIntBoolean, int unsignedBitWidth,
                boolean binaryHex, boolean timeText) {
            this.name = name;
            this.scanName = name;
            this.physicalName = physicalName;
            this.physicalTable = physicalTable;
            this.physicalSchema = physicalSchema;
            this.jdbcType = jdbcType;
            this.nativeType = nativeType;
            this.javaClassName = javaClassName;
            this.externalType = externalType;
            this.nullable = nullable;
            this.precision = precision;
            this.scale = scale;
            this.externalTextCapacity = textCapacity;
            this.json = json;
            this.largeIntegerText = largeIntegerText;
            this.mysqlTimestamp = mysqlTimestamp;
            this.tinyIntBoolean = tinyIntBoolean;
            this.unsignedBitWidth = unsignedBitWidth;
            this.binaryHex = binaryHex;
            this.timeText = timeText;
        }
    }

    private static final class ConnectionParameters {
        static Map<String, String> parse(String url, String type) {
            String protocol = isMySqlProtocol(type) ? "mysql" : "KINGBASE".equals(type) ? "kingbase8"
                    : "POSTGRESQL".equals(type) ? "postgresql" : "ORACLE".equals(type) ? "oracle"
                    : "SQLSERVER".equals(type) ? "sqlserver" : "dm";
            String prefix = "jdbc:" + protocol + ":";
            if (!value(url).regionMatches(true, 0, prefix, 0, prefix.length())) {
                throw invalid("JDBC Resource URL 协议与源数据库类型不一致");
            }
            if ("SQLSERVER".equals(type)) {
                int separator = url.indexOf(';');
                return sqlServerParameters(separator < 0 ? "" : url.substring(separator + 1));
            }
            int query = url.indexOf('?');
            return queryParameters(query < 0 ? null : url.substring(query + 1), type);
        }

        private static Map<String, String> queryParameters(String query, String type) {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (query != null) for (String item : query.split("&")) {
                if (item.isEmpty()) continue;
                String[] pair = item.split("=", 2);
                String key = decode(pair[0], type).toLowerCase(Locale.ROOT).replace("compatible_mode", "compatiblemode");
                if (!key.matches("[a-z][a-z0-9_.]*") || parameters.containsKey(key)) throw invalid("JDBC 参数名无效或重复");
                if (Arrays.asList("user", "username", "password").contains(key)) {
                    throw invalid("JDBC URL 含账号覆盖参数，不能安全复用");
                }
                String parameter = decode(pair.length == 1 ? "" : pair[1], type);
                if ("true".equalsIgnoreCase(parameter) || "1".equals(parameter)) parameter = "true";
                if ("false".equalsIgnoreCase(parameter) || "0".equals(parameter)) parameter = "false";
                parameters.put(key, parameter);
            }
            return parameters;
        }

        private static Map<String, String> sqlServerParameters(String properties) {
            Map<String, String> parameters = new LinkedHashMap<>();
            int cursor = 0;
            while (cursor < properties.length()) {
                if (properties.charAt(cursor) == ';') { cursor++; continue; }
                int equals = properties.indexOf('=', cursor);
                if (equals < 0) throw invalid("SQL Server JDBC 参数格式无效");
                String key = properties.substring(cursor, equals).trim().toLowerCase(Locale.ROOT);
                cursor = equals + 1;
                StringBuilder val = new StringBuilder();
                if (cursor < properties.length() && properties.charAt(cursor) == '{') {
                    cursor++;
                    boolean closed = false;
                    while (cursor < properties.length()) {
                        char ch = properties.charAt(cursor++);
                        if (ch == '}') {
                            if (cursor < properties.length() && properties.charAt(cursor) == '}') { val.append('}'); cursor++; }
                            else { closed = true; break; }
                        } else val.append(ch);
                    }
                    if (!closed || (cursor < properties.length() && properties.charAt(cursor) != ';')) throw invalid("SQL Server JDBC 转义参数无效");
                } else {
                    int end = properties.indexOf(';', cursor);
                    if (end < 0) end = properties.length();
                    val.append(properties, cursor, end);
                    cursor = end;
                }
                if (!key.matches("[a-z][a-z0-9_]*") || parameters.put(key, val.toString()) != null) throw invalid("SQL Server JDBC 参数名无效或重复");
                if (Arrays.asList("user", "username", "password").contains(key)) {
                    throw invalid("SQL Server JDBC URL 含账号覆盖参数，不能安全复用");
                }
            }
            return parameters;
        }

        private static String decode(String text, String type) {
            if ("DM".equals(type)) return text;
            try { return URLDecoder.decode(text, "UTF-8"); }
            catch (Exception failure) { throw invalid("JDBC URL 编码无效"); }
        }
    }
}
