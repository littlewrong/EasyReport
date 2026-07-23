package cn.easyreport.sync.dialect;

import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.StringJoiner;

public class MySqlDialect implements SchemaDialect {

    @Override
    public String createTable(TableMeta tableMeta) {
        StringBuilder sb = new StringBuilder();
        String fullName = qualifiedName(tableMeta.getSchema(), tableMeta.getName());
        sb.append("CREATE TABLE IF NOT EXISTS ").append(fullName).append(" (\n");

        // 构建列名 -> 类型映射，用于主键前缀长度判断
        Map<String, String> colTypeMap = new HashMap<>();
        for (ColumnMeta c : tableMeta.getColumns()) {
            colTypeMap.put(c.getName(), c.getColumnType() != null ? c.getColumnType().toUpperCase() : "");
        }

        List<String> lines = new ArrayList<>();
        for (ColumnMeta c : tableMeta.getColumns()) {
            lines.add(buildColumn(c));
        }
        if (!tableMeta.getPrimaryKey().isEmpty()) {
            lines.add("  PRIMARY KEY (" + joinPrimaryKeyColumns(tableMeta.getPrimaryKey(), colTypeMap) + ")");
        }
        for (IndexMeta idx : tableMeta.getIndexes()) {
            String prefix = idx.isUnique() ? "  UNIQUE KEY " : "  KEY ";
            lines.add(prefix + quote(idx.getName()) + " (" + joinColumns(idx.getColumns()) + ")");
        }
        sb.append(String.join(",\n", lines)).append("\n)");

        // 始终使用 InnoDB，忽略源数据库的引擎（如 StarRocks、OLAP 等不是 MySQL 有效引擎）
        sb.append(" ENGINE=InnoDB");
        String charset = tableMeta.getCharset() != null ? tableMeta.getCharset() : "utf8mb4";
        sb.append(" DEFAULT CHARSET=").append(charset);
        if (tableMeta.getComment() != null && !tableMeta.getComment().isEmpty()) {
            sb.append(" COMMENT='").append(escape(tableMeta.getComment())).append("'");
        }
        sb.append(";");
        return sb.toString();
    }

    protected String buildColumn(ColumnMeta c) {
        StringBuilder sb = new StringBuilder("  ").append(quote(c.getName())).append(" ").append(c.getColumnType());
        if (!c.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (c.getDefaultValue() != null && !isMySqlNoDefaultType(c.getColumnType())) {
            sb.append(" DEFAULT ").append(formatDefault(c.getDefaultValue(), c.getColumnType()));
        }
        if (c.isAutoIncrement()) {
            sb.append(" AUTO_INCREMENT");
        }
        if (c.getComment() != null && !c.getComment().isEmpty()) {
            sb.append(" COMMENT '").append(escape(c.getComment())).append("'");
        }
        return sb.toString();
    }

    /**
     * 构建主键列列表。
     * - TEXT/BLOB 强制添加 (255) 前缀（MySQL 索引要求）。
     * - 若复合 PK 总字节数超过 MySQL InnoDB 3072 字节限制（utf8mb4 每字符 4 字节），
     *   先扣除非字符串列占用的字节，再将剩余预算平分给字符串列并添加前缀。
     */
    protected String joinPrimaryKeyColumns(List<String> cols, Map<String, String> colTypeMap) {
        final int MAX_KEY_BYTES = 3072;
        final int BYTES_PER_CHAR = 4; // utf8mb4 最坏情况

        // 区分字符串列和非字符串列，分别估算字节数
        int[] charLens = new int[cols.size()];
        int stringColCount = 0;
        long stringBytes = 0;
        long nonStringBytes = 0;
        for (int i = 0; i < cols.size(); i++) {
            String type = colTypeMap.getOrDefault(cols.get(i), "");
            charLens[i] = pkCharLength(type);
            if (charLens[i] > 0) {
                stringBytes += (long) charLens[i] * BYTES_PER_CHAR;
                stringColCount++;
            } else {
                nonStringBytes += estimateMySqlIndexBytes(type);
            }
        }

        // 字符串列可用预算 = 总限制 - 非字符串列占用
        long budget = Math.max(BYTES_PER_CHAR, MAX_KEY_BYTES - nonStringBytes);
        // 各字符串列的最大前缀字符数（等分预算）
        int perColMaxChars = stringColCount > 0
            ? (int) Math.max(1, budget / stringColCount / BYTES_PER_CHAR)
            : (int) Math.max(1, budget / BYTES_PER_CHAR);

        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            String type = colTypeMap.getOrDefault(col, "");
            int charLen = charLens[i];

            if (charLen == 0) {
                // 非字符串类型，直接使用
                joiner.add(quote(col));
            } else if (stringBytes > budget) {
                // 字符串列超出预算：按等分预算截断前缀
                int prefix = Math.max(1, Math.min(charLen, perColMaxChars));
                joiner.add(quote(col) + "(" + prefix + ")");
            } else if (isMySqlNoDefaultType(type)) {
                // TEXT/BLOB 未超限但必须指定前缀
                joiner.add(quote(col) + "(255)");
            } else {
                joiner.add(quote(col));
            }
        }
        return joiner.toString();
    }

    /**
     * 估算 PK 列的字符长度（TEXT/BLOB 按 255 计，VARCHAR(n)/CHAR(n) 返回 n，非字符串返回 0）。
     */
    private int pkCharLength(String columnType) {
        if (columnType == null) return 0;
        String t = columnType.toUpperCase();
        if (isMySqlNoDefaultType(t)) return 255;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:VAR)?CHAR\\((\\d+)\\)").matcher(t);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    /**
     * 估算非字符串类型在 MySQL 索引中占用的字节数。
     */
    private int estimateMySqlIndexBytes(String columnType) {
        if (columnType == null) return 8;
        String t = columnType.toUpperCase().trim();
        if (t.startsWith("TINYINT"))   return 1;
        if (t.startsWith("SMALLINT"))  return 2;
        if (t.startsWith("MEDIUMINT")) return 3;
        if (t.startsWith("BIGINT"))    return 8;
        if (t.startsWith("INT") || t.startsWith("INTEGER")) return 4;
        if (t.startsWith("FLOAT"))     return 4;
        if (t.startsWith("DOUBLE") || t.startsWith("REAL")) return 8;
        if (t.startsWith("DATETIME"))  return 8;
        if (t.startsWith("TIMESTAMP")) return 4;
        if (t.startsWith("DATE"))      return 3;
        if (t.startsWith("TIME"))      return 3;
        if (t.startsWith("YEAR"))      return 1;
        if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC")) {
            // MySQL 每 9 位数字占 4 字节
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+)").matcher(t);
            if (m.find()) return (int)(Math.ceil(Integer.parseInt(m.group(1)) / 9.0) * 4);
            return 13;
        }
        return 8; // 保守默认值
    }

    protected String joinColumns(List<String> cols) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String col : cols) {
            joiner.add(quote(col));
        }
        return joiner.toString();
    }

    protected String qualifiedName(String schema, String table) {
        if (schema != null && !schema.isEmpty()) {
            return quote(schema) + "." + quote(table);
        }
        return quote(table);
    }

    protected String quote(String id) {
        return "`" + id + "`";
    }

    protected String escape(String s) {
        return s.replace("'", "''");
    }

    /**
     * MySQL 的 TEXT/BLOB/JSON 等大字段类型不支持 DEFAULT 值。
     */
    protected boolean isMySqlNoDefaultType(String columnType) {
        if (columnType == null) return false;
        String t = columnType.toUpperCase();
        return t.equals("TEXT") || t.equals("TINYTEXT") || t.equals("MEDIUMTEXT") || t.equals("LONGTEXT")
            || t.equals("BLOB") || t.equals("TINYBLOB") || t.equals("MEDIUMBLOB") || t.equals("LONGBLOB")
            || t.equals("JSON");
    }

    protected String formatDefault(String def, String columnType) {
        if (isBooleanLikeDefault(def) && isMySqlBooleanLikeType(columnType)) {
            return "true".equalsIgnoreCase(def) ? "1" : "0";
        }
        return formatDefault(def);
    }

    protected String formatDefault(String def) {
        // 保持原样，除非是数字或函数；简单判断是否需要引号
        boolean numeric = def.matches("^[0-9.+-]+$");
        boolean func = def.matches("(?i)^CURRENT_TIMESTAMP.*");
        boolean bitLiteral = def.matches("(?i)^b'[01]+'$");
        if (numeric || func || bitLiteral || "NULL".equalsIgnoreCase(def)) {
            return def;
        }
        return "'" + escape(def) + "'";
    }

    private boolean isBooleanLikeDefault(String def) {
        return "true".equalsIgnoreCase(def) || "false".equalsIgnoreCase(def);
    }

    private boolean isMySqlBooleanLikeType(String columnType) {
        if (columnType == null) return false;
        String t = columnType.trim().toUpperCase();
        return t.startsWith("TINYINT(1)") || t.startsWith("BIT(1)") || t.equals("BOOLEAN") || t.equals("BOOL");
    }
}
