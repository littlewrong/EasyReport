package cn.easyreport.sync.datasync;

import java.math.BigInteger;
import java.sql.Types;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Proves that a scalar Resource column fits its destination before any target rows are cleared. */
final class StarRocksJdbcResourceTargetTypes {
    private static final long DEFAULT_STRING_BYTES = 1_048_576;
    private static final Pattern DECIMAL = Pattern.compile("DECIMAL(?:V2|32|64|128)?\\((\\d+),\\s*(\\d+)\\)");
    private static final Pattern INTEGER = Pattern.compile("(TINYINT|SMALLINT|INT|INTEGER|BIGINT|LARGEINT)(?:\\(\\d+\\))?");
    private static final Pattern DATETIME = Pattern.compile("DATETIME(?:\\(([0-6])\\))?");
    private static final Pattern TEXT = Pattern.compile("(?:VAR)?CHAR\\((\\d+)\\)");

    private StarRocksJdbcResourceTargetTypes() { }

    static void verify(StarRocksJdbcResourceSource.Column column, String targetType) {
        if (column == null) throw new IllegalArgumentException("JDBC Resource 导入缺少源字段类型，尚未清空目标表");
        Target target = target(targetType);
        Scalar source = scalar(column);
        if (target == null || source == null || !fits(source, target)) {
            throw new IllegalArgumentException("JDBC Resource 字段 " + column.name + " 的源类型 "
                    + displayType(column.nativeType) + "（外表 " + displayType(column.externalType)
                    + "）不能保证无损写入目标类型 " + displayType(targetType) + "，尚未清空目标表");
        }
    }

    /** Returns bounds only when an integer needs a real Resource range proof before narrowing. */
    static IntegerRange integerNarrowing(StarRocksJdbcResourceSource.Column column, String targetType) {
        if (column == null) {
            verify(null, targetType);
            return null;
        }
        Target target = target(targetType);
        Scalar source = scalar(column);
        if (target != null && source != null && fits(source, target)) return null;
        if (source != null && source.kind == Kind.INTEGER && target != null) {
            if (target.kind == Kind.INTEGER) {
                return new IntegerRange(column.name, displayType(targetType), target.min, target.max);
            }
            if (target.kind == Kind.DECIMAL) {
                BigInteger maximum = BigInteger.TEN.pow(target.precision - target.scale).subtract(BigInteger.ONE);
                return new IntegerRange(column.name, displayType(targetType), maximum.negate(), maximum);
            }
        }
        // Other conversions retain the full-domain and fractional-precision rules.
        verify(column, targetType);
        return null;
    }

    static final class IntegerRange {
        final String columnName;
        final String targetType;
        final BigInteger minimum;
        final BigInteger maximum;

        IntegerRange(String columnName, String targetType, BigInteger minimum, BigInteger maximum) {
            this.columnName = columnName;
            this.targetType = targetType;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        void verify(BigInteger actualMinimum, BigInteger actualMaximum) {
            if (actualMinimum == null || actualMaximum == null || actualMinimum.compareTo(actualMaximum) > 0) {
                throw new IllegalArgumentException("JDBC Resource 字段 " + columnName
                        + " 的实际整数范围无效，尚未清空目标表");
            }
            if (actualMinimum.compareTo(minimum) < 0 || actualMaximum.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("JDBC Resource 字段 " + columnName
                        + " 的实际整数值超出目标类型 " + targetType + " 可表示范围，尚未清空目标表");
            }
        }
    }

    private static boolean fits(Scalar source, Target target) {
        if (target.kind == Kind.TEXT) return target.capacity >= source.maxTextBytes;
        switch (source.kind) {
            case BOOLEAN:
                if (target.kind == Kind.BOOLEAN) return true;
                // Boolean values have the exact integer domain [0, 1].
            case INTEGER:
                if (target.kind == Kind.INTEGER) return source.min.compareTo(target.min) >= 0
                        && source.max.compareTo(target.max) <= 0;
                return target.kind == Kind.DECIMAL && target.precision - target.scale >= source.integerDigits;
            case DECIMAL:
                if (target.kind == Kind.DECIMAL) return target.scale >= source.scale
                        && target.precision - target.scale >= source.precision - source.scale;
                if (target.kind == Kind.INTEGER && source.scale == 0) {
                    BigInteger bound = BigInteger.TEN.pow(source.precision).subtract(BigInteger.ONE);
                    return bound.negate().compareTo(target.min) >= 0 && bound.compareTo(target.max) <= 0;
                }
                return false;
            case FLOAT:
                return target.kind == Kind.FLOAT || target.kind == Kind.DOUBLE;
            case DOUBLE:
                return target.kind == Kind.DOUBLE;
            case DATE:
                return target.kind == Kind.DATE || target.kind == Kind.DATETIME;
            case DATETIME:
                return target.kind == Kind.DATETIME && target.scale >= source.scale;
            default:
                return false;
        }
    }

    private static Scalar scalar(StarRocksJdbcResourceSource.Column column) {
        if (column.json || column.externalTextCapacity >= 0) return null; // Handled by the caller's text/JSON probes.
        if (column.unsignedBitWidth > 0) {
            // The compatible Resource driver returns BigInteger for multi-bit MySQL
            // values. Keep the source bit domain instead of the LARGEINT transport width.
            if (column.unsignedBitWidth < 2 || column.unsignedBitWidth > 64
                    || column.jdbcType != Types.BIT || !"LARGEINT".equals(column.externalType)) return null;
            return integer(column.unsignedBitWidth, true);
        }
        String nativeType = normalize(column.nativeType);
        String base = nativeType.replaceAll("\\b(?:UNSIGNED|ZEROFILL)\\b", "")
                .replaceFirst("\\s*\\(.*$", "").trim();
        boolean unsigned = nativeType.matches(".*\\b(?:UNSIGNED|ZEROFILL)\\b.*");
        if (column.largeIntegerText || "LARGEINT".equals(base)) return integer(128, false);

        // Source preparation has already checked eligible native TINYINT values are all 0/1
        // when the driver exposes Boolean. Other unsigned Short/Integer columns retain their full range.
        if ("java.lang.Boolean".equals(column.javaClassName) && "BOOLEAN".equals(column.externalType)
                && (column.jdbcType == Types.BOOLEAN || column.jdbcType == Types.BIT || column.jdbcType == Types.TINYINT)
                && (!"BIT".equals(base) || column.precision == 1)) {
            return new Scalar(Kind.BOOLEAN, BigInteger.ZERO, BigInteger.ONE, 1, 0, 5);
        }
        int bits = nativeIntegerBits(base);
        if (bits > 0) return integer(bits, unsigned);

        if ("java.math.BigDecimal".equals(column.javaClassName)) {
            if (!validDecimal(column.precision, column.scale)) return null;
            long characters = 1L + Math.max(1, column.precision - column.scale)
                    + (column.scale > 0 ? 1L + column.scale : 0);
            return new Scalar(Kind.DECIMAL, null, null, column.precision, column.scale, characters);
        }
        if ("java.math.BigInteger".equals(column.javaClassName)
                || (("NUMERIC".equals(base) || "NUMBER".equals(base) || "DECIMAL".equals(base) || "DEC".equals(base))
                    && column.scale == 0 && integralJavaClass(column.javaClassName))) {
            if (!validDecimal(column.precision, column.scale) || column.scale != 0) return null;
            BigInteger maximum = BigInteger.TEN.pow(column.precision).subtract(BigInteger.ONE);
            return integer(unsigned ? BigInteger.ZERO : maximum.negate(), maximum);
        }
        if ("java.lang.Float".equals(column.javaClassName) && "FLOAT".equals(column.externalType)) {
            // Even an exact fixed-point expansion of the smallest binary32 subnormal fits this bound.
            return new Scalar(Kind.FLOAT, null, null, 0, 0, 160);
        }
        if ("java.lang.Double".equals(column.javaClassName) && "DOUBLE".equals(column.externalType)) {
            // Includes sign and a full fixed-point expansion of binary64 subnormals, not just scientific notation.
            return new Scalar(Kind.DOUBLE, null, null, 0, 0, 1100);
        }
        if (column.jdbcType == Types.DATE && "DATE".equals(column.externalType)) {
            return new Scalar(Kind.DATE, null, null, 0, 0, 10);
        }
        // Oracle DATE contains a clock and its standard driver returns Timestamp while
        // reporting JDBC DATE. Source preparation has already verified that combination.
        if ((column.jdbcType == Types.TIMESTAMP || (column.jdbcType == Types.DATE
                && "DATE".equals(base) && "java.sql.Timestamp".equals(column.javaClassName)))
                && "DATETIME".equals(column.externalType)
                && column.scale >= 0 && column.scale <= 6) {
            return new Scalar(Kind.DATETIME, null, null, 0, column.scale,
                    19L + (column.scale > 0 ? 1L + column.scale : 0));
        }
        switch (column.jdbcType) {
            case Types.TINYINT: return integralJavaClass(column.javaClassName) ? integer(8, unsigned) : null;
            case Types.SMALLINT: return integralJavaClass(column.javaClassName) ? integer(16, unsigned) : null;
            case Types.INTEGER: return integralJavaClass(column.javaClassName) ? integer(32, unsigned) : null;
            case Types.BIGINT: return integralJavaClass(column.javaClassName) ? integer(64, unsigned) : null;
            default: return null;
        }
    }

    private static boolean integralJavaClass(String name) {
        return "java.lang.Byte".equals(name) || "java.lang.Short".equals(name)
                || "java.lang.Integer".equals(name) || "java.lang.Long".equals(name);
    }

    private static Scalar integer(int bits, boolean unsigned) {
        BigInteger magnitude = BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1);
        return integer(unsigned ? BigInteger.ZERO : magnitude.negate(), magnitude.subtract(BigInteger.ONE));
    }

    private static Scalar integer(BigInteger minimum, BigInteger maximum) {
        int digits = Math.max(minimum.abs().toString().length(), maximum.abs().toString().length());
        int characters = Math.max(minimum.toString().length(), maximum.toString().length());
        return new Scalar(Kind.INTEGER, minimum, maximum, digits, 0, characters);
    }

    private static int nativeIntegerBits(String type) {
        switch (type) {
            case "BYTE": case "TINYINT": case "INT1": return 8;
            case "SMALLINT": case "INT2": case "SMALLSERIAL": return 16;
            case "MEDIUMINT": case "INT3": return 24;
            case "INT": case "INTEGER": case "INT4": case "SERIAL": return 32;
            case "BIGINT": case "INT8": case "BIGSERIAL": return 64;
            default: return 0;
        }
    }

    private static Target target(String raw) {
        String type = normalize(raw);
        if ("BOOLEAN".equals(type) || "BOOL".equals(type)) return new Target(Kind.BOOLEAN);
        Matcher integer = INTEGER.matcher(type);
        if (integer.matches()) {
            int bits = "LARGEINT".equals(integer.group(1)) ? 128 : nativeIntegerBits(integer.group(1));
            Scalar domain = integer(bits, false);
            Target result = new Target(Kind.INTEGER);
            result.min = domain.min; result.max = domain.max;
            return result;
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            try {
                int precision = Integer.parseInt(decimal.group(1));
                int scale = Integer.parseInt(decimal.group(2));
                if (!validDecimal(precision, scale)) return null;
                Target result = new Target(Kind.DECIMAL);
                result.precision = precision; result.scale = scale;
                return result;
            } catch (NumberFormatException invalid) { return null; }
        }
        if ("FLOAT".equals(type)) return new Target(Kind.FLOAT);
        if ("DOUBLE".equals(type) || "DOUBLE PRECISION".equals(type)) return new Target(Kind.DOUBLE);
        if ("DATE".equals(type)) return new Target(Kind.DATE);
        Matcher datetime = DATETIME.matcher(type);
        if (datetime.matches()) {
            Target result = new Target(Kind.DATETIME);
            // Bare StarRocks DATETIME stores microseconds; it is not DATETIME(0).
            result.scale = datetime.group(1) == null ? 6 : Integer.parseInt(datetime.group(1));
            return result;
        }
        if ("STRING".equals(type) || "VARCHAR".equals(type)) {
            Target result = new Target(Kind.TEXT); result.capacity = DEFAULT_STRING_BYTES; return result;
        }
        Matcher text = TEXT.matcher(type);
        if (text.matches()) {
            try {
                long capacity = Long.parseLong(text.group(1));
                if (capacity <= 0) return null;
                Target result = new Target(Kind.TEXT); result.capacity = capacity; return result;
            } catch (NumberFormatException invalid) { return null; }
        }
        return null;
    }

    private static boolean validDecimal(int precision, int scale) {
        return precision >= 1 && precision <= 38 && scale >= 0 && scale <= precision;
    }

    private static String normalize(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String displayType(String type) {
        String normalized = normalize(type);
        return normalized.matches("[A-Z][A-Z0-9_ (),]{0,79}") ? normalized : "未知类型";
    }

    private enum Kind { BOOLEAN, INTEGER, DECIMAL, FLOAT, DOUBLE, DATE, DATETIME, TEXT }

    private static final class Scalar {
        final Kind kind;
        final BigInteger min;
        final BigInteger max;
        final int precision;
        final int scale;
        final int integerDigits;
        final long maxTextBytes;
        Scalar(Kind kind, BigInteger min, BigInteger max, int precision, int scale, long maxTextBytes) {
            this.kind = kind; this.min = min; this.max = max; this.precision = precision;
            this.scale = scale; this.integerDigits = precision; this.maxTextBytes = maxTextBytes;
        }
    }

    private static final class Target {
        final Kind kind;
        BigInteger min;
        BigInteger max;
        int precision;
        int scale;
        long capacity;
        Target(Kind kind) { this.kind = kind; }
    }
}
