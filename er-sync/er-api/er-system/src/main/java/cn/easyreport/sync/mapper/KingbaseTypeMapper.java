package cn.easyreport.sync.mapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KingbaseES source types. Both the standard/PG names and common Oracle-mode
 * aliases are normalized before reusing the existing destination rules. DATE
 * versus TIMESTAMP is determined by JDBC DATA_TYPE in the schema extractor,
 * because the name DATE alone cannot distinguish the compatibility modes.
 */
public class KingbaseTypeMapper implements DatabaseTypeMapper {

    private static final List<String> TARGETS = Arrays.asList(
            "KINGBASE", "MYSQL", "TIDB", "STARROCKS", "SQLSERVER", "POSTGRESQL", "ORACLE", "DM");
    private static final Pattern NUMERIC = Pattern.compile("NUMERIC(?:\\((\\d+)(?:,(-?\\d+))?\\))?");
    private static final Pattern TEMPORAL = Pattern.compile(
            "(TIMESTAMP|TIME)(?:\\((\\d+)\\))?(?: (WITH(?: LOCAL)?|WITHOUT) TIME ZONE)?");
    private static final Pattern CHARACTER = Pattern.compile("(VARCHAR|CHAR)\\((\\d+)\\)");

    private final PostgreSqlTypeMapper standardMapper = new PostgreSqlTypeMapper();

    @Override
    public String getSourceDatabaseType() {
        return "KINGBASE";
    }

    @Override
    public boolean supportsTarget(String targetDbType) {
        return targetDbType != null && TARGETS.contains(targetDbType.toUpperCase(Locale.ROOT));
    }

    @Override
    public List<String> getSupportedTargets() {
        return new ArrayList<>(TARGETS);
    }

    @Override
    public String mapType(String targetDbType, String sourceType) {
        return resolve(targetDbType, sourceType).type;
    }

    @Override
    public boolean isLossyConversion(String targetDbType, String sourceType) {
        return resolve(targetDbType, sourceType).lossy;
    }

    private Mapping resolve(String targetDbType, String sourceType) {
        if (sourceType == null || !supportsTarget(targetDbType)
                || "KINGBASE".equalsIgnoreCase(targetDbType)) {
            return new Mapping(sourceType, false);
        }

        String target = targetDbType.toUpperCase(Locale.ROOT);
        if ("DM".equals(target)) {
            DmTargetMappings.Mapping mapped = DmTargetMappings.resolve("KINGBASE", sourceType);
            return new Mapping(mapped.type, mapped.lossy);
        }
        String type = normalize(sourceType);
        Matcher number = NUMERIC.matcher(type);
        if (number.matches()) {
            return mapNumeric(target, number);
        }
        Matcher temporal = TEMPORAL.matcher(type);
        if (temporal.matches()) {
            return mapTemporal(target, temporal);
        }

        // Kingbase TEXT/CLOB and lengthless VARCHAR must not be narrowed to
        // MySQL's 64 KiB TEXT type when reusing the standard mapping rules.
        boolean mysqlTarget = "MYSQL".equals(target) || "TIDB".equals(target);
        if (mysqlTarget && ("TEXT".equals(type) || "VARCHAR".equals(type))) {
            return new Mapping("LONGTEXT", false);
        }

        // Keep long Unicode strings usable by Oracle instead of emitting an
        // NVARCHAR2/NCHAR declaration exceeding the destination's type limits.
        Matcher character = CHARACTER.matcher(type);
        if (character.matches()) {
            long length = Long.parseLong(character.group(2));
            boolean fixed = "CHAR".equals(character.group(1));
            if ("ORACLE".equals(target) && length > (fixed ? 2000 : 4000)) {
                return new Mapping("NCLOB", false);
            }
            if ("SQLSERVER".equals(target) && length > 4000) return new Mapping("NVARCHAR(MAX)", false);
            if (mysqlTarget && length > (fixed ? 255 : 16383)) return new Mapping("LONGTEXT", false);
        }
        return new Mapping(standardMapper.mapType(target, type),
                standardMapper.isLossyConversion(target, type));
    }

    private Mapping mapNumeric(String target, Matcher number) {
        String precisionText = number.group(1);
        if (precisionText == null) {
            if ("POSTGRESQL".equals(target)) return new Mapping("NUMERIC", false);
            // A source NUMERIC without bounds can exceed every bounded target
            // decimal. Keep its textual value rather than silently rounding it.
            return numericAsText(target);
        }
        long precision = Long.parseLong(precisionText);
        long scale = number.group(2) == null ? 0 : Long.parseLong(number.group(2));
        if ("ORACLE".equals(target)) {
            // Oracle permits scale > precision and negative scales directly.
            if (precision > 38 || scale < -84 || scale > 127) return numericAsText(target);
            return new Mapping("NUMBER(" + precision + "," + scale + ")", false);
        }
        if (scale < 0) {
            precision -= scale;
            scale = 0;
        }
        // Some source types permit a scale larger than precision. A wider
        // destination precision retains the fractional values on SQL engines
        // requiring scale <= precision.
        precision = Math.max(precision, scale);
        if (("MYSQL".equals(target) || "TIDB".equals(target)) && (precision > 65 || scale > 30)) {
            return numericAsText(target);
        }
        if (("SQLSERVER".equals(target) || "STARROCKS".equals(target)) && precision > 38) {
            return numericAsText(target);
        }
        // PostgreSQL's declared NUMERIC precision is bounded, while its
        // unconstrained NUMERIC can retain the expanded integer range.
        if ("POSTGRESQL".equals(target) && precision > 1000) return new Mapping("NUMERIC", false);
        String name = "POSTGRESQL".equals(target) || "SQLSERVER".equals(target) ? "NUMERIC" : "DECIMAL";
        return new Mapping(name + "(" + precision + "," + scale + ")", false);
    }

    private Mapping numericAsText(String target) {
        if ("MYSQL".equals(target) || "TIDB".equals(target)) return new Mapping("LONGTEXT", true);
        if ("SQLSERVER".equals(target)) return new Mapping("NVARCHAR(MAX)", true);
        if ("STARROCKS".equals(target)) return new Mapping("STRING", true);
        return new Mapping("CLOB", true);
    }

    private Mapping mapTemporal(String target, Matcher temporal) {
        boolean timeOnly = "TIME".equals(temporal.group(1));
        boolean withZone = temporal.group(3) != null && temporal.group(3).startsWith("WITH")
                && !"WITHOUT".equals(temporal.group(3));
        Integer precision = temporal.group(2) == null ? null : Integer.valueOf(temporal.group(2));
        if ("STARROCKS".equals(target)) {
            return new Mapping(timeOnly ? "VARCHAR(40)" : "DATETIME",
                    timeOnly || withZone || (precision != null && precision > 0));
        }
        if ("ORACLE".equals(target) && timeOnly) {
            return new Mapping("VARCHAR2(40)", true);
        }

        int maximumPrecision = "ORACLE".equals(target) ? 9 : ("SQLSERVER".equals(target) ? 7 : 6);
        String suffix = precision == null ? "" : "(" + Math.min(precision, maximumPrecision) + ")";
        boolean lossy = precision != null && precision > maximumPrecision;
        if ("POSTGRESQL".equals(target) || "ORACLE".equals(target)) {
            return new Mapping((timeOnly ? "TIME" : "TIMESTAMP") + suffix
                    + (withZone ? " WITH TIME ZONE" : ""), lossy);
        }
        if ("SQLSERVER".equals(target)) {
            return new Mapping((timeOnly ? "TIME" : (withZone ? "DATETIMEOFFSET" : "DATETIME2")) + suffix,
                    lossy || (timeOnly && withZone));
        }
        return new Mapping((timeOnly ? "TIME" : "DATETIME") + suffix, lossy || withZone);
    }

    private String normalize(String sourceType) {
        String type = sourceType.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ")
                .replaceAll("\\s*\\(\\s*", "(").replaceAll("\\s*\\)\\s*", ") ")
                .replaceAll("\\s*,\\s*", ",").trim();
        type = type.replaceAll("\\((\\d+) (?:CHAR|BYTE)\\)", "($1)");
        type = type.replaceFirst("^(?:NUMBER|DECIMAL)(?=\\(|$)", "NUMERIC");
        type = type.replaceFirst("^(?:N?VARCHAR2|NVARCHAR|CHARACTER VARYING)(?=\\(|$)", "VARCHAR");
        type = type.replaceFirst("^(?:NCHAR|BPCHAR|CHARACTER)(?=\\(|$)", "CHAR");
        type = type.replaceFirst("^TIMESTAMPTZ(?:\\((\\d+)\\))?$", "TIMESTAMP($1) WITH TIME ZONE")
                .replace("TIMESTAMP()", "TIMESTAMP");
        type = type.replaceFirst("^TIMETZ(?:\\((\\d+)\\))?$", "TIME($1) WITH TIME ZONE")
                .replace("TIME()", "TIME");
        if (type.matches("(?:RAW|VARBINARY|BINARY)(?:\\(\\d+\\))?")
                || type.matches("(?:BLOB|LONG RAW)(?:\\(\\d+\\))?")) return "BYTEA";
        if (type.matches("(?:CLOB|NCLOB|LONG)(?:\\(\\d+\\))?")) return "TEXT";
        switch (type) {
            case "INT2": case "TINYINT": case "SMALLSERIAL": return "SMALLINT";
            case "INT": case "INT4": case "SERIAL": return "INTEGER";
            case "INT8": case "BIGSERIAL": return "BIGINT";
            case "FLOAT4": case "BINARY_FLOAT": return "REAL";
            case "FLOAT8": case "DOUBLE": case "BINARY_DOUBLE": return "DOUBLE PRECISION";
            case "BOOL": return "BOOLEAN";
            default: return type;
        }
    }

    private static final class Mapping {
        private final String type;
        private final boolean lossy;

        private Mapping(String type, boolean lossy) {
            this.type = type;
            this.lossy = lossy;
        }
    }
}
