package cn.easyreport.sync.datasync;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Timestamp;

/**
 * Default cross-database value conversion.
 */
class DefaultDataSyncValueConverter implements DataSyncValueConverter {

    private static final java.time.format.DateTimeFormatter DT_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final boolean preservePostgreSqlObject;

    DefaultDataSyncValueConverter() {
        this(false);
    }

    DefaultDataSyncValueConverter(boolean preservePostgreSqlObject) {
        this.preservePostgreSqlObject = preservePostgreSqlObject;
    }

    @Override
    public Object convertColumnValue(Object value, String sourceTypeName) {
        if ("YEAR".equalsIgnoreCase(sourceTypeName) && value instanceof java.sql.Date) {
            @SuppressWarnings("deprecation")
            int year = ((java.sql.Date) value).getYear() + 1900;
            return year;
        }
        if ("BIT".equalsIgnoreCase(sourceTypeName) && value instanceof byte[]) {
            return bytesToLong((byte[]) value);
        }
        return convertValue(value);
    }

    @Override
    public Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        String className = value.getClass().getName();
        try {
            if ("dm.jdbc.driver.DmdbTimestamp".equals(className)) {
                return safeTimestamp((Timestamp) value.getClass().getMethod("toTimestamp").invoke(value));
            }
            if (className.startsWith("oracle.sql.TIMESTAMP") || className.equals("oracle.sql.DATE")) {
                Timestamp ts = (Timestamp) value.getClass().getMethod("timestampValue").invoke(value);
                return safeTimestamp(ts);
            }
            if (className.equals("org.postgresql.util.PGobject")) {
                if (preservePostgreSqlObject) {
                    return value;
                }
                return value.getClass().getMethod("getValue").invoke(value);
            }
            if (isKingbaseObject(value)) {
                Object text = value.getClass().getMethod("getValue").invoke(value);
                if (text == null) return null;
                if (preservePostgreSqlObject) {
                    String type = (String) value.getClass().getMethod("getType").invoke(value);
                    if (isCommonPostgreSqlType(type)) {
                        // Never pass a Kingbase driver's native object to the PostgreSQL driver.
                        // Retain the SQL type for JSON/etc. even when its connection binds strings as VARCHAR.
                        Class<?> pgClass = Class.forName("org.postgresql.util.PGobject");
                        Object pgObject = pgClass.getDeclaredConstructor().newInstance();
                        pgClass.getMethod("setType", String.class).invoke(pgObject, type.toLowerCase(java.util.Locale.ROOT));
                        pgClass.getMethod("setValue", String.class).invoke(pgObject, String.valueOf(text));
                        return pgObject;
                    }
                }
                return text;
            }
            if (value instanceof Clob) {
                Clob clob = (Clob) value;
                return clob.getSubString(1, (int) clob.length());
            }
            if (value instanceof Blob) {
                Blob blob = (Blob) value;
                return blob.getBytes(1, (int) blob.length());
            }
        } catch (Exception e) {
            return value.toString();
        }
        if (value instanceof Timestamp) {
            return safeTimestamp((Timestamp) value);
        }
        if (value instanceof java.time.OffsetDateTime) {
            Timestamp ts = Timestamp.from(((java.time.OffsetDateTime) value).toInstant());
            return safeTimestamp(ts);
        }
        if (value instanceof java.time.LocalDateTime) {
            Timestamp ts = Timestamp.valueOf((java.time.LocalDateTime) value);
            return safeTimestamp(ts);
        }
        return value;
    }

    protected static boolean isKingbaseObject(Object value) {
        if (value == null) return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if ("com.kingbase8.util.KBobject".equals(type.getName())) return true;
        }
        return false;
    }

    private static boolean isCommonPostgreSqlType(String type) {
        if (type == null) return false;
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "json": case "jsonb": case "xml": case "uuid": case "interval":
            case "inet": case "cidr": case "macaddr": case "macaddr8":
            case "bit": case "varbit": case "point": case "lseg": case "box":
            case "path": case "polygon": case "circle": case "line":
            case "money": case "tsvector": case "tsquery": return true;
            default: return false;
        }
    }

    private static Object safeTimestamp(Timestamp ts) {
        java.time.LocalDateTime ldt = ts.toLocalDateTime();
        int year = ldt.getYear();
        if (year >= 1970 && year <= 2038) {
            return ts;
        }
        return ldt.format(DT_FMT);
    }

    private static long bytesToLong(byte[] bytes) {
        long result = 0;
        for (byte b : bytes) {
            result = (result << 8) | (b & 0xFF);
        }
        return result;
    }
}
