package cn.easyreport.sync.dialect;

import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.TableMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * StarRocks SQL dialect for generating CREATE TABLE DDL.
 *
 * StarRocks特点：
 * 1. 支持 DUPLICATE KEY / UNIQUE KEY / AGGREGATE KEY 等表模型
 * 2. 必须指定 DISTRIBUTED BY HASH 分布策略
 * 3. 不支持 AUTO_INCREMENT（使用替代方案）
 */
public class StarRocksDialect implements SchemaDialect {

    @Override
    public String createTable(TableMeta tableMeta) {
        StringBuilder sb = new StringBuilder();
        String fullName = qualifiedName(tableMeta.getSchema(), tableMeta.getName());
        sb.append("CREATE TABLE IF NOT EXISTS ").append(fullName).append(" (\n");

        // StarRocks PRIMARY KEY 模型不支持 DECIMAL 类型作为主键列，需在生成列定义前降级
        if (!tableMeta.getPrimaryKey().isEmpty()) {
            java.util.Map<String, ColumnMeta> colMap = new java.util.HashMap<>();
            for (ColumnMeta c : tableMeta.getColumns()) colMap.put(c.getName(), c);
            for (String pkCol : tableMeta.getPrimaryKey()) {
                ColumnMeta c = colMap.get(pkCol);
                if (c != null && c.getColumnType() != null) {
                    String promoted = promoteDecimalPkType(c.getColumnType());
                    if (promoted != null) c.setColumnType(promoted);
                }
            }
        }

        List<String> lines = new ArrayList<>();
        for (ColumnMeta c : tableMeta.getColumns()) {
            lines.add(buildColumn(c));
        }

        sb.append(String.join(",\n", lines)).append("\n)");

        // StarRocks表模型：
        // - 有主键：使用 PRIMARY KEY 模型（自动支持 upsert，无需 ON DUPLICATE KEY UPDATE）
        // - 无主键：使用 DUPLICATE KEY 模型（允许重复数据）
        if (!tableMeta.getPrimaryKey().isEmpty()) {
            // 使用 PRIMARY KEY 模型，StarRocks 会自动处理主键冲突（upsert 语义）
            sb.append("\nPRIMARY KEY (").append(joinColumns(tableMeta.getPrimaryKey())).append(")");
        } else if (!tableMeta.getColumns().isEmpty()) {
            // 没有主键时，使用 DUPLICATE KEY 模型
            List<String> firstCol = new ArrayList<>();
            firstCol.add(tableMeta.getColumns().get(0).getName());
            sb.append("\nDUPLICATE KEY (").append(joinColumns(firstCol)).append(")");
        }

        // 分布策略：使用主键或第一列作为分布列
        if (!tableMeta.getPrimaryKey().isEmpty()) {
            String distKey = tableMeta.getPrimaryKey().get(0);
            sb.append("\nDISTRIBUTED BY HASH(").append(quote(distKey)).append(") BUCKETS 10");
        } else if (!tableMeta.getColumns().isEmpty()) {
            String distKey = tableMeta.getColumns().get(0).getName();
            sb.append("\nDISTRIBUTED BY HASH(").append(quote(distKey)).append(") BUCKETS 10");
        }

        // 表属性
        List<String> properties = new ArrayList<>();
        properties.add("\"replication_num\" = \"1\""); // 默认1副本

        if (!properties.isEmpty()) {
            sb.append("\nPROPERTIES (\n  ");
            sb.append(String.join(",\n  ", properties));
            sb.append("\n)");
        }

        sb.append(";");
        return sb.toString();
    }

    protected String buildColumn(ColumnMeta c) {
        StringBuilder sb = new StringBuilder("  ").append(quote(c.getName())).append(" ").append(c.getColumnType());

        // StarRocks 不支持 AUTO_INCREMENT，忽略该属性
        // 对于自增列，建议使用应用层生成ID或使用UUID

        // StarRocks 默认列为 NOT NULL，必须显式声明 NULL
        if (!c.isNullable()) {
            sb.append(" NOT NULL");
        } else {
            sb.append(" NULL");
        }

        // DEFAULT值处理
        if (c.getDefaultValue() != null && !c.getDefaultValue().isEmpty()) {
            // StarRocks对DEFAULT有一些限制，需要谨慎处理
            String defaultValue = c.getDefaultValue();
            // 跳过CURRENT_TIMESTAMP等函数（StarRocks可能不支持所有函数作为默认值）
            if (!defaultValue.toUpperCase().contains("CURRENT_TIMESTAMP")) {
                sb.append(" DEFAULT ").append(formatDefault(defaultValue));
            }
        }

        if (c.getComment() != null && !c.getComment().isEmpty()) {
            sb.append(" COMMENT '").append(escape(c.getComment())).append("'");
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
        return quote(table);
    }

    protected String quote(String id) {
        return "`" + id + "`";
    }

    protected String escape(String s) {
        return s.replace("'", "''");
    }

    /**
     * StarRocks PRIMARY KEY 模型不支持 DECIMAL 类型。
     * - DECIMAL(p, 0) 且 p ≤ 18 → BIGINT
     * - DECIMAL(p, 0) 且 p ≤ 38 → LARGEINT
     * - 其他 DECIMAL（有小数位）→ BIGINT（截断小数，有损）
     * 返回 null 表示无需转换。
     */
    private String promoteDecimalPkType(String columnType) {
        if (columnType == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?i)^DECIMAL\\((\\d+),\\s*(\\d+)\\)$").matcher(columnType.trim());
        if (!m.matches()) return null;
        int precision = Integer.parseInt(m.group(1));
        int scale     = Integer.parseInt(m.group(2));
        if (scale == 0 && precision <= 18) return "BIGINT";
        if (scale == 0 && precision <= 38) return "LARGEINT";
        // 有小数位：降级为 BIGINT（丢失精度）
        return "BIGINT";
    }

    protected String formatDefault(String def) {
        // NULL 和函数不需要引号
        if ("NULL".equalsIgnoreCase(def)) {
            return def;
        }
        boolean func = def.matches("(?i)^CURRENT_.*");
        if (func) {
            return def;
        }
        // 位字面量 b'0' / b'1' 转为 StarRocks 的 "0" / "1"
        if (def.matches("(?i)^b'[01]+'$")) {
            String bits = def.substring(2, def.length() - 1);
            return "\"" + Integer.parseInt(bits, 2) + "\"";
        }
        // StarRocks 的 DEFAULT 值统一使用双引号包裹（包括数值）
        return "\"" + def.replace("\"", "\\\"") + "\"";
    }
}
