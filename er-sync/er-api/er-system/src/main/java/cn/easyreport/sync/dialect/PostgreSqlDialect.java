package cn.easyreport.sync.dialect;

import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.IndexMeta;
import cn.easyreport.sync.model.TableMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * PostgreSQL DDL 生成器
 */
public class PostgreSqlDialect implements SchemaDialect {

    @Override
    public String createTable(TableMeta tableMeta) {
        StringBuilder sb = new StringBuilder();
        String fullName = qualifiedName(tableMeta.getSchema(), tableMeta.getName());

        // CREATE TABLE IF NOT EXISTS
        sb.append("CREATE TABLE IF NOT EXISTS ").append(fullName).append(" (\n");

        List<String> lines = new ArrayList<>();

        // 列定义
        for (ColumnMeta c : tableMeta.getColumns()) {
            lines.add(buildColumn(c));
        }

        // 主键
        if (tableMeta.getPrimaryKey() != null && !tableMeta.getPrimaryKey().isEmpty()) {
            lines.add("  PRIMARY KEY (" + joinColumns(tableMeta.getPrimaryKey()) + ")");
        }

        sb.append(String.join(",\n", lines));
        sb.append("\n)");

        return sb.toString();
    }

    protected String buildColumn(ColumnMeta c) {
        StringBuilder sb = new StringBuilder("  ")
            .append(quote(c.getName()))
            .append(" ")
            .append(c.getColumnType()); // Use the formatted type directly

        if (!c.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (c.getDefaultValue() != null && !c.getDefaultValue().isEmpty()) {
            sb.append(" DEFAULT ").append(formatDefault(c.getDefaultValue()));
        }

        if (c.getComment() != null && !c.getComment().isEmpty()) {
            sb.append(" /* ").append(escape(c.getComment())).append(" */");
        }

        return sb.toString();
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
        return "\"public\"." + quote(table);
    }

    protected String quote(String id) {
        return "\"" + id + "\"";
    }

    protected String escape(String s) {
        return s.replace("'", "''");
    }

    protected String formatDefault(String def) {
        // Keep numeric values, functions, and NULL unquoted
        boolean numeric = def.matches("^[0-9.+-]+$");
        boolean func = def.matches("(?i)^(CURRENT_TIMESTAMP|NOW\\(\\)|CURRENT_DATE).*");
        if (numeric || func || "NULL".equalsIgnoreCase(def)) {
            return def;
        }
        // MySQL 位字面量 b'0'/b'1' 转为 PostgreSQL 布尔值
        if (def.matches("(?i)^b'[01]+'$")) {
            String bits = def.substring(2, def.length() - 1);
            return Integer.parseInt(bits, 2) == 0 ? "FALSE" : "TRUE";
        }
        return "'" + escape(def) + "'";
    }

    public List<String> generateCreateIndexDdl(TableMeta tableMeta) {
        List<String> result = new ArrayList<>();
        if (tableMeta.getIndexes() == null || tableMeta.getIndexes().isEmpty()) {
            return result;
        }

        String schema = tableMeta.getSchema();
        String schemaPrefix = (schema != null && !schema.isEmpty()) ? "\"" + schema + "\"." : "\"public\".";
        String tableName = schemaPrefix + "\"" + tableMeta.getName() + "\"";

        for (IndexMeta index : tableMeta.getIndexes()) {
            StringBuilder sb = new StringBuilder();
            
            // CREATE INDEX IF NOT EXISTS
            sb.append("CREATE ");
            if (index.isUnique()) {
                sb.append("UNIQUE ");
            }
            sb.append("INDEX IF NOT EXISTS \"").append(index.getName()).append("\" ");
            sb.append("ON ").append(tableName).append(" (");

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

}
