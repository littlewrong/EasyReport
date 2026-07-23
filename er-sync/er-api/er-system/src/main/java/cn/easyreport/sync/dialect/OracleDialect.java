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
 * Oracle DDL 生成器
 *
 * Oracle 特性：
 * 1. 标识符用双引号: "SCHEMA"."TABLE"
 * 2. 不支持 CREATE TABLE IF NOT EXISTS，由调用方先判断 tableExists
 * 3. 主键约束内联定义
 * 4. 注释通过单独的 COMMENT ON 语句
 * 5. 索引通过单独的 CREATE INDEX 语句
 * 6. AUTO_INCREMENT 使用 GENERATED ALWAYS AS IDENTITY (12c+)
 */
public class OracleDialect implements SchemaDialect {

    @Override
    public String createTable(TableMeta tableMeta) {
        StringBuilder sb = new StringBuilder();
        String fullName = qualifiedName(tableMeta.getSchema(), tableMeta.getName());

        sb.append("CREATE TABLE ").append(fullName).append(" (\n");

        // Oracle 不允许 CLOB/NCLOB/BLOB 作主键，必须在生成列定义前截断
        if (tableMeta.getPrimaryKey() != null && !tableMeta.getPrimaryKey().isEmpty()) {
            Map<String, ColumnMeta> colMap = new HashMap<>();
            for (ColumnMeta c : tableMeta.getColumns()) {
                colMap.put(c.getName(), c);
            }
            for (String pkCol : tableMeta.getPrimaryKey()) {
                ColumnMeta c = colMap.get(pkCol);
                if (c != null && c.getColumnType() != null) {
                    String t = c.getColumnType().toUpperCase();
                    if (t.equals("CLOB") || t.equals("NCLOB")) {
                        c.setColumnType("NVARCHAR2(2000)");
                    } else if (t.equals("BLOB")) {
                        c.setColumnType("RAW(2000)");
                    }
                }
            }
        }

        List<String> lines = new ArrayList<>();

        // 列定义
        for (ColumnMeta c : tableMeta.getColumns()) {
            lines.add(buildColumn(c));
        }

        // 主键
        if (tableMeta.getPrimaryKey() != null && !tableMeta.getPrimaryKey().isEmpty()) {
            String pkName = "PK_" + tableMeta.getName().toUpperCase();
            lines.add("  CONSTRAINT \"" + pkName + "\" PRIMARY KEY (" + joinColumns(tableMeta.getPrimaryKey()) + ")");
        }

        sb.append(String.join(",\n", lines));
        sb.append("\n)");

        return sb.toString();
    }

    /**
     * 生成注释 DDL 语句（表注释 + 列注释）
     */
    public List<String> generateCommentDdl(TableMeta tableMeta) {
        List<String> result = new ArrayList<>();
        String fullName = qualifiedName(tableMeta.getSchema(), tableMeta.getName());

        // 表注释
        if (tableMeta.getComment() != null && !tableMeta.getComment().isEmpty()) {
            result.add("COMMENT ON TABLE " + fullName + " IS '" + escape(tableMeta.getComment()) + "'");
        }

        // 列注释
        if (tableMeta.getColumns() != null) {
            for (ColumnMeta col : tableMeta.getColumns()) {
                if (col.getComment() != null && !col.getComment().isEmpty()) {
                    result.add("COMMENT ON COLUMN " + fullName + ".\"" + col.getName() + "\" IS '" + escape(col.getComment()) + "'");
                }
            }
        }

        return result;
    }

    /**
     * 生成索引 DDL 语句
     */
    public List<String> generateCreateIndexDdl(TableMeta tableMeta) {
        List<String> result = new ArrayList<>();
        if (tableMeta.getIndexes() == null || tableMeta.getIndexes().isEmpty()) {
            return result;
        }

        String fullName = qualifiedName(tableMeta.getSchema(), tableMeta.getName());

        for (IndexMeta index : tableMeta.getIndexes()) {
            StringBuilder sb = new StringBuilder("CREATE ");
            if (index.isUnique()) {
                sb.append("UNIQUE ");
            }
            sb.append("INDEX ");

            // 索引名需要带 Schema 前缀
            if (tableMeta.getSchema() != null && !tableMeta.getSchema().isEmpty()) {
                sb.append("\"").append(tableMeta.getSchema().toUpperCase()).append("\".\"").append(index.getName()).append("\"");
            } else {
                sb.append("\"").append(index.getName()).append("\"");
            }

            sb.append(" ON ").append(fullName).append(" (");

            StringJoiner cols = new StringJoiner(", ");
            for (String col : index.getColumns()) {
                cols.add("\"" + col + "\"");
            }
            sb.append(cols);
            sb.append(")");

            result.add(sb.toString());
        }

        return result;
    }

    private String buildColumn(ColumnMeta c) {
        // 修正超出 Oracle 限制的 NVARCHAR2/NCHAR 长度
        String columnType = capOracleNVarcharLength(c.getColumnType());
        StringBuilder sb = new StringBuilder("  ")
            .append(quote(c.getName()))
            .append(" ")
            .append(columnType);

        // AUTO_INCREMENT -> GENERATED ALWAYS AS IDENTITY
        if (c.isAutoIncrement()) {
            sb.append(" GENERATED ALWAYS AS IDENTITY");
        }

        // Oracle 要求 DEFAULT 在 NOT NULL 之前
        if (c.getDefaultValue() != null && !c.getDefaultValue().isEmpty() && !c.isAutoIncrement()) {
            sb.append(" DEFAULT ").append(formatDefault(c.getDefaultValue()));
        }

        if (!c.isNullable()) {
            sb.append(" NOT NULL");
        }

        return sb.toString();
    }

    /**
     * Oracle NVARCHAR2 最大 4000，NCHAR 最大 2000；超出时降级为 NCLOB/NCHAR(2000)。
     */
    private String capOracleNVarcharLength(String columnType) {
        if (columnType == null) return columnType;
        java.util.regex.Matcher m;
        // NVARCHAR2(n) -> 最大 4000
        m = java.util.regex.Pattern.compile("(?i)^NVARCHAR2\\((\\d+)\\)$").matcher(columnType);
        if (m.matches()) {
            int len = Integer.parseInt(m.group(1));
            if (len > 4000) return "NCLOB";
            return columnType;
        }
        // NCHAR(n) -> 最大 2000
        m = java.util.regex.Pattern.compile("(?i)^NCHAR\\((\\d+)\\)$").matcher(columnType);
        if (m.matches()) {
            int len = Integer.parseInt(m.group(1));
            if (len > 2000) return "NCHAR(2000)";
            return columnType;
        }
        return columnType;
    }

    private String joinColumns(List<String> cols) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String col : cols) {
            joiner.add(quote(col));
        }
        return joiner.toString();
    }

    private String qualifiedName(String schema, String table) {
        if (schema != null && !schema.isEmpty()) {
            return quote(schema.toUpperCase()) + "." + quote(table.toUpperCase());
        }
        return quote(table.toUpperCase());
    }

    private String quote(String id) {
        return "\"" + id + "\"";
    }

    private String escape(String s) {
        return s.replace("'", "''");
    }

    private String formatDefault(String def) {
        boolean numeric = def.matches("^[0-9.+-]+$");
        boolean func = def.matches("(?i)^(SYSDATE|SYSTIMESTAMP|CURRENT_TIMESTAMP|NULL).*");
        if (numeric || func || "NULL".equalsIgnoreCase(def)) {
            return def;
        }
        // MySQL 位字面量 b'0'/b'1' 转为数值
        if (def.matches("(?i)^b'[01]+'$")) {
            String bits = def.substring(2, def.length() - 1);
            return String.valueOf(Integer.parseInt(bits, 2));
        }
        return "'" + escape(def) + "'";
    }
}
