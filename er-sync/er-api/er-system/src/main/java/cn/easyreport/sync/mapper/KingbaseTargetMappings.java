package cn.easyreport.sync.mapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Common SQL types supported by KingbaseES, with the existing PostgreSQL target
 * rules as a baseline. The rule list is copied so that old target mappings are
 * never changed when Kingbase-specific rules are added.
 */
final class KingbaseTargetMappings {

    private KingbaseTargetMappings() {
    }

    static List<TypeMappingRule> from(String sourceDbType, List<TypeMappingRule> baseline) {
        List<TypeMappingRule> rules = new ArrayList<>(baseline);

        if ("MYSQL".equals(sourceDbType) || "TIDB".equals(sourceDbType)) {
            String width = "(?:\\s*\\(\\s*\\d+\\s*\\))?";
            String unsigned = "\\s+(?:UNSIGNED(?:\\s+ZEROFILL)?|ZEROFILL)";
            add(rules, "TINYINT" + width + unsigned, "SMALLINT");
            add(rules, "SMALLINT" + width + unsigned, "INTEGER");
            add(rules, "MEDIUMINT" + width + unsigned, "INTEGER");
            add(rules, "INT(?:EGER)?" + width + unsigned, "BIGINT");
            add(rules, "BIGINT" + width + unsigned, "NUMERIC(20,0)");
            add(rules, "(?:DECIMAL|NUMERIC)\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)(?:" + unsigned + ")?", "NUMERIC($1,$2)");
            add(rules, "(?:DECIMAL|NUMERIC)(?:" + unsigned + ")?", "NUMERIC(10,0)");
            add(rules, "(?:FLOAT|DOUBLE)(?:\\(\\d+,\\d+\\))?(?:" + unsigned + ")?", "DOUBLE PRECISION");
            add(rules, "DATETIME\\((\\d+)\\)", "TIMESTAMP($1)");
            add(rules, "TIMESTAMP\\((\\d+)\\)", "TIMESTAMP($1)");
            add(rules, "TIME\\((\\d+)\\)", "TIME($1)");
            // The common JDBC converter reads MySQL BIT as an integer.
            add(rules, "BIT\\((?:[2-9]|[1-5][0-9]|6[0-4])\\)", "NUMERIC(20,0)");
        } else if ("ORACLE".equals(sourceDbType)) {
            add(rules, "NUMBER\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)", "NUMERIC($1,$2)");
            add(rules, "NUMBER\\((\\d+)\\)", "NUMERIC($1,0)");
            add(rules, "N?VARCHAR2\\((\\d+)\\s+(?:CHAR|BYTE)\\)", "VARCHAR($1)");
            add(rules, "N?CHAR\\((\\d+)\\s+(?:CHAR|BYTE)\\)", "CHAR($1)");
            add(rules, "TIMESTAMP\\(([0-6])\\)", "TIMESTAMP($1)");
            add(rules, "TIMESTAMP\\(([0-6])\\) WITH(?: LOCAL)? TIME ZONE", "TIMESTAMP($1) WITH TIME ZONE");
            lossy(rules, "TIMESTAMP\\([7-9]\\)", "TIMESTAMP(6)");
            lossy(rules, "TIMESTAMP\\([7-9]\\) WITH(?: LOCAL)? TIME ZONE", "TIMESTAMP(6) WITH TIME ZONE");
            add(rules, "TIMESTAMP WITH(?: LOCAL)? TIME ZONE", "TIMESTAMP WITH TIME ZONE");
        } else if ("SQLSERVER".equals(sourceDbType)) {
            add(rules, "DATETIME2\\(([0-6])\\)", "TIMESTAMP($1)");
            lossy(rules, "DATETIME2\\(7\\)", "TIMESTAMP(6)");
            add(rules, "DATETIMEOFFSET\\(([0-6])\\)", "TIMESTAMP($1) WITH TIME ZONE");
            lossy(rules, "DATETIMEOFFSET\\(7\\)", "TIMESTAMP(6) WITH TIME ZONE");
            // The existing SQL Server extractor can return only the base type
            // name; its default fractional precision is seven digits.
            lossy(rules, "DATETIME2", "TIMESTAMP(6)");
            lossy(rules, "DATETIMEOFFSET", "TIMESTAMP(6) WITH TIME ZONE");
            add(rules, "TIME\\(([0-6])\\)", "TIME($1)");
            lossy(rules, "TIME\\(7\\)", "TIME(6)");
            lossy(rules, "TIME", "TIME(6)");
            add(rules, "(?:TIMESTAMP|ROWVERSION)", "BYTEA");
        } else if ("STARROCKS".equals(sourceDbType)) {
            add(rules, "LARGEINT", "NUMERIC(39,0)");
            add(rules, "DECIMAL(?:32|64|128|V3)?\\((\\d+),(\\d+)\\)", "NUMERIC($1,$2)");
            add(rules, "VARBINARY(?:\\(\\d+\\))?", "BYTEA");
            add(rules, "DATETIME\\((\\d+)\\)", "TIMESTAMP($1)");
        }

        // Older server versions do not accept negative NUMERIC scales. Expanding
        // the integer precision preserves the range of already-stored values.
        rules.add(new TypeMappingRule("(?:NUMBER|NUMERIC|DECIMAL)\\(\\s*(\\d+)\\s*,\\s*-(\\d+)\\s*\\)",
                "NUMERIC", 0, false, "Expand negative scale without narrowing the value range") {
            @Override
            public String apply(String sourceType) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(getSourcePattern(),
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sourceType.trim());
                if (!matcher.matches()) return sourceType;
                long precision = Long.parseLong(matcher.group(1)) + Long.parseLong(matcher.group(2));
                return "NUMERIC(" + precision + ",0)";
            }
        });
        rules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        return rules;
    }

    private static void add(List<TypeMappingRule> rules, String source, String target) {
        rules.add(new TypeMappingRule(source, target, 0, false, "KingbaseES common SQL type mapping"));
    }

    private static void lossy(List<TypeMappingRule> rules, String source, String target) {
        rules.add(new TypeMappingRule(source, target, 0, true, "KingbaseES fractional seconds support at most six digits"));
    }
}
