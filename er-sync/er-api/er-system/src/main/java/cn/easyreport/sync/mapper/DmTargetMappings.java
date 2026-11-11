package cn.easyreport.sync.mapper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DM8 target rules. Existing database rule lists are not reused or changed. */
final class DmTargetMappings {
    private static final Pattern NUMBER = Pattern.compile("(?:NUMBER|NUMERIC|DECIMAL|DEC)(?:\\((\\d+)(?:,(-?\\d+))?\\))?");
    private static final Pattern CHARACTER = Pattern.compile("(N?VARCHAR2?|N?CHAR|CHARACTER VARYING|CHARACTER)(?:\\((\\d+)(?: (?:CHAR|BYTE))?\\))?");
    private static final Pattern TEMPORAL = Pattern.compile("(DATETIME2?|TIMESTAMP|TIME)(?:\\((\\d+)\\))?(?: (WITH(?: LOCAL)?|WITHOUT) TIME ZONE)?");

    private DmTargetMappings() {
    }

    /**
     * Stateless rules allow the existing mapper API to report a per-type lossy
     * result without changing any original source-to-destination rules.
     */
    static List<TypeMappingRule> rules(String sourceDatabase) {
        return Arrays.asList(rule(sourceDatabase, false), rule(sourceDatabase, true));
    }

    private static TypeMappingRule rule(final String sourceDatabase, final boolean lossy) {
        return new TypeMappingRule(".*", "$0", lossy ? 1 : 0, lossy, "DM8 destination mapping") {
            @Override
            public boolean matches(String sourceType) {
                return sourceType != null && resolve(sourceDatabase, sourceType).lossy == lossy;
            }

            @Override
            public String apply(String sourceType) {
                return sourceType == null ? null : resolve(sourceDatabase, sourceType).type;
            }
        };
    }

    static Mapping resolve(String sourceDatabase, String sourceType) {
        if (sourceType == null) return value(null);
        String source = sourceDatabase.toUpperCase(Locale.ROOT);
        String type = normalize(sourceType);
        boolean mysql = "MYSQL".equals(source) || "TIDB".equals(source);

        if ("SQLSERVER".equals(source)) {
            if ("TIMESTAMP".equals(type) || "ROWVERSION".equals(type)) return value("BINARY(8)");
            if ("DATETIME".equals(type)) return value("TIMESTAMP(3)");
            if ("SMALLDATETIME".equals(type)) return value("TIMESTAMP(0)");
            if ("DATETIME2".equals(type)) return loss("TIMESTAMP(6)");
            if ("DATETIMEOFFSET".equals(type)) return loss("TIMESTAMP(6) WITH TIME ZONE");
            if (type.startsWith("DATETIMEOFFSET(")) type = type.replace("DATETIMEOFFSET", "TIMESTAMP") + " WITH TIME ZONE";
            if ("TIME".equals(type)) return loss("TIME(6)");
            if ("TINYINT".equals(type)) return value("SMALLINT");
            if (type.matches("N?(?:VAR)?CHAR\\(MAX\\)")) return value("CLOB");
            if ("MONEY".equals(type)) return value("NUMERIC(19,4)");
            if ("SMALLMONEY".equals(type)) return value("NUMERIC(10,4)");
        }
        if ("ORACLE".equals(source) && "DATE".equals(type)) return value("TIMESTAMP(0)");

        if (mysql) {
            boolean unsigned = type.contains("UNSIGNED") || type.contains("ZEROFILL");
            String base = type.replaceAll("\\s+(?:UNSIGNED|ZEROFILL)", "");
            String integer = base.replaceAll("\\(\\d+\\)$", "");
            if (unsigned) {
                switch (integer) {
                    case "TINYINT": return value("SMALLINT");
                    case "SMALLINT": case "MEDIUMINT": return value("INTEGER");
                    case "INT": case "INTEGER": return value("BIGINT");
                    case "BIGINT": return value("NUMERIC(20,0)");
                    default: break;
                }
            }
            switch (integer) {
                case "TINYINT": return value("TINYINT");
                case "SMALLINT": return value("SMALLINT");
                case "MEDIUMINT": case "INT": case "INTEGER": return value("INTEGER");
                case "BIGINT": return value("BIGINT");
                default: break;
            }
            Matcher bit = Pattern.compile("BIT(?:\\((\\d+)\\))?").matcher(base);
            if (bit.matches()) return value(bit.group(1) == null || "1".equals(bit.group(1)) ? "BIT" : "NUMERIC(20,0)");
            if (base.matches("TIME(?:\\(\\d+\\))?")) {
                // MySQL TIME also represents durations outside a single day.
                return loss("VARCHAR(40 CHAR)");
            }
            type = base;
        }

        Matcher numeric = NUMBER.matcher(type);
        if (numeric.matches()) {
            if (numeric.group(1) == null) {
                // Both Oracle NUMBER and DM's unconstrained NUMERIC support
                // a dynamic scale; explicit scale zero would discard fractions.
                if ("ORACLE".equals(source)) return value("NUMERIC");
                // PostgreSQL/KES can hold more significant digits than DM.
                return mysql ? value("NUMERIC(10,0)") : loss("CLOB");
            }
            long precision = Long.parseLong(numeric.group(1));
            long scale = numeric.group(2) == null ? 0 : Long.parseLong(numeric.group(2));
            if (scale < 0) { precision -= scale; scale = 0; }
            precision = Math.max(precision, scale);
            return precision >= 1 && precision <= 38 && scale <= 38
                ? value("NUMERIC(" + precision + "," + scale + ")") : loss("CLOB");
        }

        Matcher character = CHARACTER.matcher(type);
        if (character.matches()) {
            if (character.group(2) == null) return value("CLOB");
            long length = Long.parseLong(character.group(2));
            boolean fixed = character.group(1).matches("N?CHAR|CHARACTER");
            // Explicit character semantics avoids narrowing UTF-8 strings to bytes.
            // A conservative inline threshold also avoids assuming a large DM page size.
            if (length > 400) return fixed ? loss("CLOB") : value("CLOB");
            return length > 0 ? value((fixed ? "CHAR(" : "VARCHAR(") + length + " CHAR)") : loss("CLOB");
        }
        if (type.matches("(?:RAW|VARBINARY|BINARY)(?:\\((?:MAX|\\d+)\\))?")) {
            Matcher binary = Pattern.compile("(RAW|VARBINARY|BINARY)\\((\\d+)\\)").matcher(type);
            if (binary.matches() && Long.parseLong(binary.group(2)) <= 2000) {
                return value(("BINARY".equals(binary.group(1)) ? "BINARY(" : "VARBINARY(") + binary.group(2) + ")");
            }
            return value("BLOB");
        }
        if (type.matches("(?:BIT(?: VARYING)?|VARBIT)(?:\\(\\d+\\))?")) {
            Matcher bits = Pattern.compile(".*\\((\\d+)\\)").matcher(type);
            if (bits.matches() && Long.parseLong(bits.group(1)) <= 1000) return loss("VARCHAR(" + bits.group(1) + " CHAR)");
            return loss("CLOB");
        }

        Matcher temporal = TEMPORAL.matcher(type);
        if (temporal.matches()) {
            boolean time = "TIME".equals(temporal.group(1));
            int precision = temporal.group(2) == null ? (mysql || "STARROCKS".equals(source) ? 0 : 6)
                : Integer.parseInt(temporal.group(2));
            boolean zone = temporal.group(3) != null && !"WITHOUT".equals(temporal.group(3));
            String target = (time ? "TIME(" : "TIMESTAMP(") + Math.min(precision, 6) + ")" + (zone ? " WITH TIME ZONE" : "");
            return precision > 6 || "WITH LOCAL".equals(temporal.group(3)) ? loss(target) : value(target);
        }

        switch (type) {
            case "SMALLINT": case "INT2": case "SMALLSERIAL": return value("SMALLINT");
            case "INT": case "INTEGER": case "INT4": case "MEDIUMINT": case "SERIAL": return value("INTEGER");
            case "BIGINT": case "INT8": case "BIGSERIAL": return value("BIGINT");
            case "TINYINT": case "BYTE": return value("TINYINT");
            case "REAL": case "FLOAT4": case "BINARY_FLOAT": return value("REAL");
            case "DOUBLE": case "DOUBLE PRECISION": case "FLOAT8": case "BINARY_DOUBLE": return value("DOUBLE PRECISION");
            case "BOOL": case "BOOLEAN": return value("BIT");
            case "DATE": return value("DATE");
            case "TEXT": case "TINYTEXT": case "MEDIUMTEXT": case "LONGTEXT":
            case "CLOB": case "NCLOB": case "LONG": case "LONGVARCHAR": case "NTEXT": return value("CLOB");
            case "BYTEA": case "BLOB": case "TINYBLOB": case "MEDIUMBLOB": case "LONGBLOB":
            case "LONG RAW": case "IMAGE": case "LONGVARBINARY": return value("BLOB");
            case "UUID": case "UNIQUEIDENTIFIER": return value("VARCHAR(36 CHAR)");
            case "JSON": case "JSONB": case "XML": case "XMLTYPE": return loss("CLOB");
            case "YEAR": case "YEAR(4)": case "YEAR(2)": return value("SMALLINT");
            case "LARGEINT": return loss("CLOB"); // signed 128-bit values require up to 39 decimal digits
            default: break;
        }
        Matcher floating = Pattern.compile("(?:FLOAT|DOUBLE(?: PRECISION)?)(?:\\((\\d+)(?:,\\d+)?\\))?").matcher(type);
        if (floating.matches()) {
            if ("ORACLE".equals(source) && type.startsWith("FLOAT")
                && (floating.group(1) == null || Long.parseLong(floating.group(1)) > 53)) return loss("CLOB");
            return value("DOUBLE PRECISION");
        }
        Matcher starRocksDecimal = Pattern.compile("DECIMAL(?:32|64|128|V3)\\((\\d+),(\\d+)\\)").matcher(type);
        if (starRocksDecimal.matches()) return resolve(source, "NUMERIC(" + starRocksDecimal.group(1) + "," + starRocksDecimal.group(2) + ")");
        // Custom domains, arrays and enum/set semantics cannot be reconstructed by this metadata model.
        return loss("CLOB");
    }

    static String normalize(String value) {
        String type = value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ")
            .replaceAll("\\s*\\(\\s*", "(").replaceAll("\\s*\\)", ")")
            .replaceAll("\\s*,\\s*", ",");
        type = type.replaceFirst("^CHARACTER VARYING(?=\\(|$)", "VARCHAR")
            .replaceFirst("^BPCHAR(?=\\(|$)", "CHAR");
        type = type.replaceFirst("^TIMESTAMPTZ(?:\\((\\d+)\\))?$", "TIMESTAMP($1) WITH TIME ZONE").replace("TIMESTAMP()", "TIMESTAMP");
        type = type.replaceFirst("^TIMETZ(?:\\((\\d+)\\))?$", "TIME($1) WITH TIME ZONE").replace("TIME()", "TIME");
        return type;
    }

    static Mapping value(String type) { return new Mapping(type, false); }
    static Mapping loss(String type) { return new Mapping(type, true); }

    static final class Mapping {
        final String type;
        final boolean lossy;
        Mapping(String type, boolean lossy) { this.type = type; this.lossy = lossy; }
    }
}
