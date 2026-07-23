package cn.easyreport.sync.dialect;

import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * TiDB 数据库方言实现
 * TiDB 是基于 MySQL 协议的分布式数据库，但在某些语法和特性上有所差异：
 * 1. 不支持外键约束
 * 2. 不支持部分 AUTO_INCREMENT 特性（需要使用 AUTO_RANDOM）
 * 3. 不支持某些存储引擎特定的语法
 * 4. 对索引和分区的支持有差异
 */
public class TiDbDialect implements SchemaDialect {

    @Override
    public String createTable(TableMeta tableMeta) {
        StringBuilder sb = new StringBuilder();
        String fullName = qualifiedName(tableMeta.getSchema(), tableMeta.getName());
        sb.append("CREATE TABLE IF NOT EXISTS ").append(fullName).append(" (\n");

        List<String> lines = new ArrayList<>();

        // 构建列定义
        for (ColumnMeta c : tableMeta.getColumns()) {
            lines.add(buildColumn(c));
        }

        // 构建列名 -> 类型映射，用于主键前缀长度判断
        Map<String, String> colTypeMap = new HashMap<>();
        for (ColumnMeta c : tableMeta.getColumns()) {
            colTypeMap.put(c.getName(), c.getColumnType() != null ? c.getColumnType().toUpperCase() : "");
        }

        // 构建主键定义
        if (!tableMeta.getPrimaryKey().isEmpty()) {
            lines.add("  PRIMARY KEY (" + joinPrimaryKeyColumns(tableMeta.getPrimaryKey(), colTypeMap) + ")");
        }

        // 构建索引定义
        for (IndexMeta idx : tableMeta.getIndexes()) {
            String prefix = idx.isUnique() ? "  UNIQUE KEY " : "  KEY ";
            lines.add(prefix + quote(idx.getName()) + " (" + joinColumns(idx.getColumns()) + ")");
        }

        sb.append(String.join(",\n", lines)).append("\n)");

        // TiDB 表选项 - 始终使用 InnoDB，忽略源数据库的引擎（如 StarRocks、OLAP 等）
        sb.append(" ENGINE=InnoDB");

        String charset = tableMeta.getCharset() != null ? tableMeta.getCharset() : "utf8mb4";
        sb.append(" DEFAULT CHARSET=").append(charset);

        // TiDB 支持表注释
        if (tableMeta.getComment() != null && !tableMeta.getComment().isEmpty()) {
            sb.append(" COMMENT='").append(escape(tableMeta.getComment())).append("'");
        }

        sb.append(";");
        return sb.toString();
    }

    /**
     * 构建列定义
     * TiDB 特殊处理：
     * - AUTO_INCREMENT 支持与 MySQL 类似
     * - 可以使用 AUTO_RANDOM 替代 AUTO_INCREMENT（用于分布式主键）
     */
    protected String buildColumn(ColumnMeta c) {
        StringBuilder sb = new StringBuilder("  ").append(quote(c.getName())).append(" ").append(c.getColumnType());

        // 可空性
        if (!c.isNullable()) {
            sb.append(" NOT NULL");
        }

        // 默认值（TEXT/BLOB/JSON 类型不支持 DEFAULT）
        if (c.getDefaultValue() != null && !isNoDefaultType(c.getColumnType())) {
            sb.append(" DEFAULT ").append(formatDefault(c.getDefaultValue(), c.getColumnType()));
        }

        // 自增列
        // TiDB 支持 AUTO_INCREMENT，也可以使用 AUTO_RANDOM 提升性能
        if (c.isAutoIncrement()) {
            sb.append(" AUTO_INCREMENT");
        }

        // 列注释
        if (c.getComment() != null && !c.getComment().isEmpty()) {
            sb.append(" COMMENT '").append(escape(c.getComment())).append("'");
        }

        return sb.toString();
    }

    protected boolean isNoDefaultType(String columnType) {
        if (columnType == null) return false;
        String t = columnType.toUpperCase();
        return t.equals("TEXT") || t.equals("TINYTEXT") || t.equals("MEDIUMTEXT") || t.equals("LONGTEXT")
            || t.equals("BLOB") || t.equals("TINYBLOB") || t.equals("MEDIUMBLOB") || t.equals("LONGBLOB")
            || t.equals("JSON");
    }

    protected String joinPrimaryKeyColumns(List<String> cols, Map<String, String> colTypeMap) {
        final int MAX_KEY_BYTES = 3072;
        final int BYTES_PER_CHAR = 4; // utf8mb4 最坏情况

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

        long budget = Math.max(BYTES_PER_CHAR, MAX_KEY_BYTES - nonStringBytes);
        int perColMaxChars = stringColCount > 0
            ? (int) Math.max(1, budget / stringColCount / BYTES_PER_CHAR)
            : (int) Math.max(1, budget / BYTES_PER_CHAR);

        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            String type = colTypeMap.getOrDefault(col, "");
            int charLen = charLens[i];

            if (charLen == 0) {
                joiner.add(quote(col));
            } else if (stringBytes > budget) {
                int prefix = Math.max(1, Math.min(charLen, perColMaxChars));
                joiner.add(quote(col) + "(" + prefix + ")");
            } else if (isNoDefaultType(type)) {
                joiner.add(quote(col) + "(255)");
            } else {
                joiner.add(quote(col));
            }
        }
        return joiner.toString();
    }

    private int pkCharLength(String columnType) {
        if (columnType == null) return 0;
        String t = columnType.toUpperCase();
        if (isNoDefaultType(t)) return 255;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:VAR)?CHAR\\((\\d+)\\)").matcher(t);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

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
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+)").matcher(t);
            if (m.find()) return (int)(Math.ceil(Integer.parseInt(m.group(1)) / 9.0) * 4);
            return 13;
        }
        return 8;
    }

    /**
     * 拼接列名列表（用于主键、索引等）
     */
    protected String joinColumns(List<String> cols) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String col : cols) {
            joiner.add(quote(col));
        }
        return joiner.toString();
    }

    /**
     * 构建完全限定表名
     * TiDB 使用数据库名.表名的方式
     */
    protected String qualifiedName(String schema, String table) {
        if (schema != null && !schema.isEmpty()) {
            return quote(schema) + "." + quote(table);
        }
        return quote(table);
    }

    /**
     * TiDB 使用反引号作为标识符引用符
     */
    protected String quote(String id) {
        return "`" + id + "`";
    }

    /**
     * 转义单引号字符串
     */
    protected String escape(String s) {
        return s.replace("'", "''");
    }

    /**
     * 格式化默认值
     * TiDB 对默认值的处理与 MySQL 类似：
     * - 数字不需要引号
     * - 函数（如 CURRENT_TIMESTAMP）不需要引号
     * - NULL 不需要引号
     * - 字符串需要单引号
     */
    protected String formatDefault(String def, String columnType) {
        if (isBooleanLikeDefault(def) && isTiDbBooleanLikeType(columnType)) {
            return "true".equalsIgnoreCase(def) ? "1" : "0";
        }
        return formatDefault(def);
    }

    protected String formatDefault(String def) {
        // 数字
        boolean numeric = def.matches("^[0-9.+-]+$");
        // 函数（CURRENT_TIMESTAMP、NOW()等）
        boolean func = def.matches("(?i)^(CURRENT_TIMESTAMP|NOW\\(\\)|UUID\\(\\)).*");
        // 位字面量（b'0', b'1' 等）
        boolean bitLiteral = def.matches("(?i)^b'[01]+'$");
        // NULL 值
        if (numeric || func || bitLiteral || "NULL".equalsIgnoreCase(def)) {
            return def;
        }
        // 字符串需要加引号
        return "'" + escape(def) + "'";
    }

    private boolean isBooleanLikeDefault(String def) {
        return "true".equalsIgnoreCase(def) || "false".equalsIgnoreCase(def);
    }

    private boolean isTiDbBooleanLikeType(String columnType) {
        if (columnType == null) return false;
        String t = columnType.trim().toUpperCase();
        return t.startsWith("TINYINT(1)") || t.startsWith("BIT(1)") || t.equals("BOOLEAN") || t.equals("BOOL");
    }
}
