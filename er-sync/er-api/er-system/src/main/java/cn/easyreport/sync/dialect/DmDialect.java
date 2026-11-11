package cn.easyreport.sync.dialect;

import cn.easyreport.sync.dm.DmJdbcSupport;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** DM8 DDL with native IDENTITY and schema-scoped indexes. */
public class DmDialect implements SchemaDialect {
    @Override
    public String createTable(TableMeta table) {
        List<String> columns = new ArrayList<>();
        for (ColumnMeta column : table.getColumns()) {
            String type = column.getColumnType();
            if (type == null || type.trim().isEmpty()) throw new IllegalArgumentException("DM column type is required");
            if (table.getPrimaryKey() != null && table.getPrimaryKey().contains(column.getName())
                    && type.trim().matches("(?i)^(CLOB|NCLOB|BLOB|TEXT|IMAGE)(?:\\s*\\(.*\\))?$")) {
                throw new IllegalArgumentException("达梦主键列 " + column.getName() + " 映射为 " + type
                    + "，大字段类型不能用作主键；请调整源键或预建并验证目标表的键类型，不能自动截断主键值");
            }
            if (column.isAutoIncrement()) {
                if (type.matches("(?i)^(TINYINT|SMALLINT|BYTE|BIT)$")) type = "INT";
                if (!type.matches("(?i)^(INT|INTEGER|BIGINT|(?:DEC|DECIMAL|NUMERIC|NUMBER)\\(\\s*\\d+\\s*(?:,\\s*0\\s*)?\\))$")) {
                    throw new IllegalArgumentException("DM IDENTITY requires an integer or fixed integral decimal: " + type);
                }
            }
            StringBuilder definition = new StringBuilder("  ").append(quote(column.getName()))
                .append(" ").append(type);
            if (column.isAutoIncrement()) {
                definition.append(" IDENTITY(1,1)");
            } else if (column.getDefaultValue() != null) {
                String defaultValue;
                if (Boolean.TRUE.equals(column.getDefaultValueLiteral())) {
                    defaultValue = "'" + escape(column.getDefaultValue()) + "'";
                } else if (Boolean.FALSE.equals(column.getDefaultValueLiteral())) {
                    defaultValue = column.getDefaultValue();
                } else {
                    defaultValue = formatDefault(column.getDefaultValue(), type);
                }
                definition.append(" DEFAULT ").append(defaultValue);
            }
            if (!column.isNullable()) definition.append(" NOT NULL");
            columns.add(definition.toString());
        }
        if (table.getPrimaryKey() != null && !table.getPrimaryKey().isEmpty()) {
            columns.add("  PRIMARY KEY (" + joinColumns(table.getPrimaryKey()) + ")");
        }
        return "CREATE TABLE IF NOT EXISTS " + qualifiedName(table.getSchema(), table.getName()) + " (\n"
            + String.join(",\n", columns) + "\n) STORAGE (USING LONG ROW)";
    }

    /** Each statement is executed independently, after CREATE TABLE succeeds. */
    public List<String> postCreateStatements(TableMeta table) {
        List<String> result = new ArrayList<>();
        String name = qualifiedName(table.getSchema(), table.getName());
        if (table.getIndexes() != null) {
            for (IndexMeta index : table.getIndexes()) {
                if (index.getColumns() == null || index.getColumns().isEmpty()) continue;
                if (index.isUnique() && index.getColumns().equals(table.getPrimaryKey())) continue;
                result.add("CREATE " + (index.isUnique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS "
                    + quote(indexName(table.getName(), index.getName())) + " ON " + name + " (" + joinColumns(index.getColumns()) + ")");
            }
        }
        if (table.getComment() != null && !table.getComment().isEmpty()) {
            result.add("COMMENT ON TABLE " + name + " IS '" + escape(table.getComment()) + "'");
        }
        for (ColumnMeta column : table.getColumns()) {
            if (column.getComment() != null && !column.getComment().isEmpty()) {
                result.add("COMMENT ON COLUMN " + name + "." + quote(column.getName()) + " IS '" + escape(column.getComment()) + "'");
            }
        }
        return result;
    }

    private String qualifiedName(String schema, String table) {
        return (schema == null || schema.isEmpty() ? "" : quote(schema) + ".") + quote(table);
    }

    private String joinColumns(List<String> columns) {
        StringJoiner result = new StringJoiner(", ");
        for (String column : columns) result.add(quote(column));
        return result.toString();
    }

    private String quote(String name) {
        return DmJdbcSupport.quoteIdentifier(name);
    }

    private String escape(String value) {
        return value.replace("'", "''");
    }

    private String indexName(String table, String index) {
        // MySQL index names are table-scoped; DM index names belong to a schema.
        // A tuple hash avoids collisions when readable prefixes share an underscore boundary.
        String base = table + "_" + index;
        StringBuilder suffix = new StringBuilder("_");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((table + "\u0000" + index).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 8; i++) suffix.append(String.format(java.util.Locale.ROOT, "%02x", hash[i] & 0xff));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        int byteBudget = 128 - suffix.length();
        int end = 0;
        int bytes = 0;
        while (end < base.length()) {
            int codePoint = base.codePointAt(end);
            int charCount = Character.charCount(codePoint);
            int nextBytes = base.substring(end, end + charCount).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + nextBytes > byteBudget) break;
            bytes += nextBytes;
            end += charCount;
        }
        return base.substring(0, end) + suffix;
    }

    private String formatDefault(String value, String columnType) {
        String trimmed = value.trim();
        String number = trimmed;
        while (number.startsWith("(") && number.endsWith(")")) number = number.substring(1, number.length() - 1).trim();
        if (number.matches("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")) trimmed = number;
        boolean booleanType = columnType != null && columnType.matches("(?i)^(BOOL|BOOLEAN|BIT)$");
        if (trimmed.matches("(?i)^b'[01]+'$")) {
            String bits = trimmed.substring(2, trimmed.length() - 1);
            if (booleanType) return bits.matches("0+") ? "0" : "1";
            return new BigInteger(bits, 2).toString();
        }
        if (booleanType) {
            if (trimmed.matches("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")) {
                return new BigDecimal(trimmed).signum() == 0 ? "0" : "1";
            }
            if ("TRUE".equalsIgnoreCase(trimmed)) return "1";
            if ("FALSE".equalsIgnoreCase(trimmed)) return "0";
        }
        if (trimmed.matches("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")) return trimmed;
        if (trimmed.matches("(?i)^(NULL|TRUE|FALSE|CURRENT_DATE|CURRENT_TIME|CURRENT_TIMESTAMP|LOCALTIMESTAMP)(?:\\(\\d*\\))?$")) {
            return trimmed;
        }
        if (trimmed.matches("(?i)^(NOW\\(\\)|SYSDATE|SYSTIMESTAMP|GETDATE\\(\\))$")) return "CURRENT_TIMESTAMP";
        if (trimmed.matches("(?is)^'(?:[^']|'')*'$")) return trimmed;
        return "'" + escape(value) + "'";
    }
}
