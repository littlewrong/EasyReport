package cn.easyreport.sync.datasync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Application-side, strict UTF-8 transport for binary columns mapped to StarRocks text. */
final class StarRocksBinaryTextTransfer {
    private static final int MAX_TEXT_BYTES = 1_048_576;
    private static final Pattern TEXT_CAPACITY = Pattern.compile("(?:VAR)?CHAR\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern TARGET = Pattern.compile("(?:`(?:[^`]|``)+`(?:\\.`(?:[^`]|``)+`)?|(?:[A-Za-z_][A-Za-z0-9_]*\\.)?[A-Za-z_][A-Za-z0-9_]*)");
    private final Map<String, TextColumn> columns;
    private final DataSyncCancellation cancellation;
    private final String sourceType;

    private StarRocksBinaryTextTransfer(Map<String, TextColumn> columns, DataSyncCancellation cancellation, String sourceType) {
        this.columns = columns;
        this.cancellation = cancellation;
        this.sourceType = sourceType;
    }

    static StarRocksBinaryTextTransfer prepare(Connection source, Connection target, String schema, String table,
            String targetTable, List<String> sourceCols, List<String> targetCols,
            DataSyncCancellation cancellation) throws SQLException {
        return prepare("MYSQL", source, target, schema, table, targetTable, sourceCols, targetCols, cancellation);
    }

    static StarRocksBinaryTextTransfer prepare(String sourceType, Connection source, Connection target, String schema, String table,
            String targetTable, List<String> sourceCols, List<String> targetCols,
            DataSyncCancellation cancellation) throws SQLException {
        stopped(cancellation);
        sourceType = sourceType == null ? "MYSQL" : sourceType.toUpperCase(Locale.ROOT);
        if (sourceCols == null || sourceCols.isEmpty() || targetCols == null || sourceCols.size() != targetCols.size()) {
            throw new SQLException("BLOB 文本字段配置无效，尚未清空目标表");
        }
        List<String> selected = new ArrayList<>();
        for (String column : sourceCols) selected.add(quote(column, sourceType));
        for (String column : targetCols) quote(column, "MYSQL");
        List<Integer> binaries = new ArrayList<>();
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + String.join(", ", selected) + " FROM " + qualified(source, schema, table, sourceType) + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata.getColumnCount() != sourceCols.size()) throw new BinaryTextException("BLOB 源字段元数据不完整，尚未清空目标表", "HY000");
            for (int index = 0; index < sourceCols.size(); index++) {
                stopped(cancellation);
                int type = metadata.getColumnType(index + 1);
                // BIT(n) also returns byte[], but its unsigned numeric semantics must remain separate.
                if (type == Types.BLOB || type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY) {
                    binaries.add(index);
                }
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("读取 BLOB 源字段元数据失败，尚未清空目标表", failure);
        }
        Map<String, TextColumn> columns = new LinkedHashMap<>();
        if (binaries.isEmpty()) return new StarRocksBinaryTextTransfer(columns, cancellation, sourceType);
        if (targetTable == null || !TARGET.matcher(targetTable).matches() || hasControl(targetTable)) {
            throw new SQLException("BLOB 文本目标表名无效，尚未清空目标表");
        }
        Map<String, String> targetTypes = new LinkedHashMap<>();
        stopped(cancellation);
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery("DESC " + targetTable)) {
            while (rows.next()) {
                stopped(cancellation);
                String name = rows.getString("Field");
                String type = rows.getString("Type");
                if (name != null && type != null) targetTypes.put(key(name), type.trim().toUpperCase(Locale.ROOT));
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("读取 BLOB 目标字段元数据失败，尚未清空目标表", failure);
        }
        // The application transfer writes every configured column. A missing scalar column
        // must fail here too, before a BLOB-triggered JDBC transfer can truncate the table.
        for (String column : targetCols) {
            if (!targetTypes.containsKey(key(column))) throw new SQLException("BLOB 文本导入缺少目标字段 " + column + "，尚未清空目标表");
        }
        for (Integer index : binaries) {
            String type = targetTypes.get(key(targetCols.get(index)));
            int capacity = textCapacity(type);
            if (capacity >= 0) columns.put(key(sourceCols.get(index)), new TextColumn(sourceCols.get(index), capacity));
        }
        return new StarRocksBinaryTextTransfer(columns, cancellation, sourceType);
    }

    boolean hasTextColumns() {
        return !columns.isEmpty();
    }

    boolean contains(String column) {
        return column != null && columns.containsKey(key(column));
    }

    Object read(ResultSet rows, String column) throws SQLException {
        return read(rows, column, cancellation);
    }

    private Object read(ResultSet rows, String column, DataSyncCancellation stop) throws SQLException {
        stopped(stop);
        TextColumn textColumn = columns.get(key(column));
        if (textColumn == null) return rows.getObject(column);
        try {
            Object value = rows.getObject(column);
            if (value == null) return null;
            if (value instanceof byte[]) {
                byte[] bytes = (byte[]) value;
                requireCapacity(textColumn, bytes.length);
                return decode(bytes, textColumn, stop);
            } else if (value instanceof Blob) {
                // Closing the handle is a try-with-resources cleanup: a release failure
                // is secondary to a capacity, stream, encoding or cancellation failure.
                try (BlobHandle handle = new BlobHandle((Blob) value, textColumn)) {
                    requireCapacity(textColumn, handle.blob.length());
                    try (InputStream stream = handle.blob.getBinaryStream()) {
                        return decode(readBytes(stream, textColumn, stop), textColumn, stop);
                    }
                } catch (IOException failure) {
                    throw new BinaryTextException("源 BLOB 字段 " + textColumn.name + " 的字节流读取失败", "HY000");
                }
            } else {
                // Some Connector/J settings change getObject's class; getBinaryStream retains raw bytes.
                try (InputStream stream = rows.getBinaryStream(column)) {
                    return decode(readBytes(stream, textColumn, stop), textColumn, stop);
                } catch (IOException failure) {
                    throw new BinaryTextException("源 BLOB 字段 " + textColumn.name + " 的字节流读取失败", "HY000");
                }
            }
        } catch (SQLException failure) {
            stopped(stop);
            throw safe("源 BLOB 字段 " + textColumn.name + " 的读取失败", failure);
        }
    }

    private static String decode(byte[] bytes, TextColumn textColumn, DataSyncCancellation stop) throws SQLException {
        stopped(stop);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new BinaryTextException("源 BLOB 字段 " + textColumn.name + " 不是有效的 UTF-8 文本", "22021");
        }
    }

    /** Validate every effective value before the executor can truncate the target. */
    void preflight(Connection source, String schema, String table, String tsField,
            DataSyncCancellation cancellation) throws SQLException {
        if (!hasTextColumns()) return;
        List<String> selected = new ArrayList<>();
        for (TextColumn column : columns.values()) selected.add(quote(column.name, sourceType));
        String sql = "SELECT " + String.join(", ", selected) + " FROM " + qualified(source, schema, table, sourceType);
        if (tsField != null && !tsField.trim().isEmpty()) sql += " WHERE " + quote(tsField.trim(), sourceType) + " IS NOT NULL";
        stopped(cancellation);
        try (PreparedStatement statement = source.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(isMySqlProtocol(sourceType) ? Integer.MIN_VALUE : 128);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    stopped(cancellation);
                    for (TextColumn column : columns.values()) read(rows, column.name, cancellation);
                }
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            if (failure instanceof DataSyncCancellation.SyncStoppedException) throw failure;
            String message = failure instanceof BinaryTextException ? failure.getMessage() : "BLOB 文本预检读取失败";
            // Driver diagnostics can contain raw values or credentials; do not attach them.
            throw new SQLException(message + "，尚未清空目标表", failure.getSQLState(), failure.getErrorCode());
        }
        stopped(cancellation);
    }

    private static byte[] readBytes(InputStream stream, TextColumn column, DataSyncCancellation cancellation)
            throws IOException, SQLException {
        if (stream == null) throw new BinaryTextException("源 BLOB 字段 " + column.name + " 未返回有效字节流", "HY000");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(column.capacity, 8192));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer, 0, Math.min(buffer.length, column.capacity - output.size() + 1))) != -1) {
            stopped(cancellation);
            requireCapacity(column, (long) output.size() + count);
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void requireCapacity(TextColumn column, long length) throws SQLException {
        if (length < 0 || length > column.capacity) throw new BinaryTextException("源 BLOB 字段 " + column.name
                + " 的 UTF-8 字节数超出目标文本字段容量（" + column.capacity + " 字节）", "22001");
    }

    private static int textCapacity(String type) throws SQLException {
        if ("STRING".equals(type)) return MAX_TEXT_BYTES;
        Matcher match = TEXT_CAPACITY.matcher(type);
        if (!match.matches()) return -1;
        try {
            long capacity = Long.parseLong(match.group(1));
            if (capacity > 0) return (int) Math.min(capacity, MAX_TEXT_BYTES);
        } catch (NumberFormatException invalid) {
            // Invalid metadata must never silently relax capacity checks.
        }
        throw new SQLException("BLOB 目标文本字段容量无效，尚未清空目标表");
    }

    private static String qualified(Connection source, String schema, String table, String sourceType) throws SQLException {
        if ("ORACLE".equals(sourceType)) {
            if (schema == null || schema.isEmpty()) schema = source.getMetaData().getUserName();
            schema = oracleIdentifier(schema);
            table = oracleIdentifier(table);
        }
        if ("SQLSERVER".equals(sourceType)) {
            return (schema == null || schema.isEmpty() ? "" : quote(schema, sourceType) + ".") + "[dbo]." + quote(table, sourceType);
        }
        return schema == null || schema.isEmpty() ? quote(table, sourceType) : quote(schema, sourceType) + "." + quote(table, sourceType);
    }

    private static String oracleIdentifier(String identifier) {
        if (identifier == null) return null;
        if (identifier.length() >= 2 && identifier.startsWith("\"") && identifier.endsWith("\"")) {
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier.toUpperCase(Locale.ROOT);
    }

    private static String quote(String identifier, String sourceType) throws SQLException {
        if (identifier == null || identifier.isEmpty() || hasControl(identifier)) throw new SQLException("BLOB 文本字段标识符无效，尚未清空目标表");
        if (isMySqlProtocol(sourceType)) return "`" + identifier.replace("`", "``") + "`";
        if ("SQLSERVER".equals(sourceType)) return "[" + identifier.replace("]", "]]") + "]";
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static boolean isMySqlProtocol(String type) {
        return "MYSQL".equals(type) || "TIDB".equals(type) || "STARROCKS".equals(type);
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 32 || character == 127);
    }

    private static String key(String column) {
        return column.toLowerCase(Locale.ROOT);
    }

    private static void stopped(DataSyncCancellation cancellation) throws DataSyncCancellation.SyncStoppedException {
        if (cancellation != null) cancellation.checkStopped();
    }

    private static final class TextColumn {
        final String name;
        final int capacity;
        TextColumn(String name, int capacity) { this.name = name; this.capacity = capacity; }
    }

    private static final class BinaryTextException extends SQLException {
        BinaryTextException(String message, String state) { super(message, state); }
        BinaryTextException(String message, String state, int code) { super(message, state, code); }
    }

    private static SQLException safe(String message, SQLException failure) {
        SQLException result = failure instanceof DataSyncCancellation.SyncStoppedException
                ? new DataSyncCancellation.SyncStoppedException()
                : new BinaryTextException(failure instanceof BinaryTextException ? failure.getMessage() : message,
                        failure.getSQLState(), failure.getErrorCode());
        for (Throwable suppressed : failure.getSuppressed()) {
            result.addSuppressed(suppressed instanceof SQLException
                    ? safe("BLOB 资源清理失败", (SQLException) suppressed) : new SQLException("BLOB 资源清理失败"));
        }
        return result;
    }

    private static final class BlobHandle implements AutoCloseable {
        final Blob blob;
        final TextColumn column;
        BlobHandle(Blob blob, TextColumn column) { this.blob = blob; this.column = column; }
        @Override public void close() throws SQLException {
            try { blob.free(); }
            catch (SQLException failure) { throw safe("源 BLOB 字段 " + column.name + " 的资源释放失败", failure); }
        }
    }
}
