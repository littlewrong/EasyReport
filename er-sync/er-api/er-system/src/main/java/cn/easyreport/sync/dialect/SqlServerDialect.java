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
 * SQL Server SQL dialect for generating CREATE TABLE DDL.
 */
public class SqlServerDialect implements SchemaDialect {

    @Override
    public String createTable(TableMeta tableMeta) {
        StringBuilder sb = new StringBuilder();
        String fullName = qualifiedName(tableMeta);
        sb.append("CREATE TABLE ").append(fullName).append(" (\n");

        // SQL Server 不允许 VARCHAR(MAX)/NVARCHAR(MAX) 作为主键列，必须在生成列定义前截断
        if (!tableMeta.getPrimaryKey().isEmpty()) {
            Map<String, ColumnMeta> colMap = new HashMap<>();
            for (ColumnMeta c : tableMeta.getColumns()) {
                colMap.put(c.getName(), c);
            }
            for (String pkCol : tableMeta.getPrimaryKey()) {
                ColumnMeta c = colMap.get(pkCol);
                if (c != null && c.getColumnType() != null) {
                    String t = c.getColumnType().toUpperCase();
                    if (t.equals("VARCHAR(MAX)")) {
                        c.setColumnType("VARCHAR(450)");
                    } else if (t.equals("NVARCHAR(MAX)")) {
                        c.setColumnType("NVARCHAR(450)");
                    }
                }
            }
        }

        List<String> lines = new ArrayList<>();
        for (ColumnMeta c : tableMeta.getColumns()) {
            lines.add(buildColumn(c));
        }

        // Add primary key constraint
        if (!tableMeta.getPrimaryKey().isEmpty()) {
            lines.add("  CONSTRAINT [PK_" + tableMeta.getName() + "] PRIMARY KEY CLUSTERED (" + joinColumns(tableMeta.getPrimaryKey()) + ")");
        }

        sb.append(String.join(",\n", lines)).append("\n)");
        sb.append(";");

        // Add indexes separately
        for (IndexMeta idx : tableMeta.getIndexes()) {
            sb.append("\n\n");
            sb.append("IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = '").append(idx.getName()).append("' AND object_id = OBJECT_ID('").append(fullName).append("'))\n");
            sb.append("CREATE ");
            if (idx.isUnique()) {
                sb.append("UNIQUE ");
            }
            sb.append("NONCLUSTERED INDEX [").append(idx.getName()).append("] ON ").append(fullName);
            sb.append(" (").append(joinColumns(idx.getColumns())).append(");");
        }

        return sb.toString();
    }

    protected String buildColumn(ColumnMeta c) {
        StringBuilder sb = new StringBuilder("  [").append(c.getName()).append("] ").append(c.getColumnType());

        // Identity column (auto-increment)
        if (c.isAutoIncrement()) {
            sb.append(" IDENTITY(1,1)");
        }

        // Nullable
        if (!c.isNullable()) {
            sb.append(" NOT NULL");
        } else {
            sb.append(" NULL");
        }

        // Default value
        if (c.getDefaultValue() != null && !c.getDefaultValue().isEmpty() && !c.isAutoIncrement()) {
            String defaultValue = c.getDefaultValue();
            // Skip MySQL-specific defaults
            if (!defaultValue.toUpperCase().contains("CURRENT_TIMESTAMP")) {
                sb.append(" DEFAULT ").append(formatDefault(defaultValue));
            }
        }

        return sb.toString();
    }

    protected String joinColumns(List<String> cols) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String col : cols) {
            joiner.add("[" + col + "]");
        }
        return joiner.toString();
    }

    protected String qualifiedName(String schema, String table) {
        if (schema != null && !schema.isEmpty()) {
            return "[" + schema + "].[" + table + "]";
        }
        return "[dbo].[" + table + "]";
    }

    protected String qualifiedName(TableMeta tableMeta) {
        String database = tableMeta.getDatabase();
        String schema = tableMeta.getSchema();
        String table = tableMeta.getName();

        // 如果指定了数据库，使用完全限定名 [database].[schema].[table]
        if (database != null && !database.isEmpty()) {
            if (schema != null && !schema.isEmpty()) {
                return "[" + database + "].[" + schema + "].[" + table + "]";
            }
            return "[" + database + "].[dbo].[" + table + "]";
        }

        // 否则使用 [schema].[table]
        return qualifiedName(schema, table);
    }

    protected String formatDefault(String def) {
        // Check if numeric
        boolean numeric = def.matches("^[0-9.+-]+$");
        if (numeric || "NULL".equalsIgnoreCase(def)) {
            return def;
        }
        // MySQL 位字面量 b'0'/b'1' 转为数值
        if (def.matches("(?i)^b'[01]+'$")) {
            String bits = def.substring(2, def.length() - 1);
            return String.valueOf(Integer.parseInt(bits, 2));
        }
        // String default needs quotes
        return "'" + def.replace("'", "''") + "'";
    }
}
