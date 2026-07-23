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
