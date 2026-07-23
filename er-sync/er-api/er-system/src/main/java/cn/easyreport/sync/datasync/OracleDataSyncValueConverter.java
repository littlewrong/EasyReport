package cn.easyreport.sync.datasync;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Timestamp;

/**
 * Oracle target conversion keeps timestamp values as JDBC timestamps.
 */
class OracleDataSyncValueConverter extends DefaultDataSyncValueConverter {

    @Override
    public Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        String className = value.getClass().getName();
        try {
            if (className.startsWith("oracle.sql.TIMESTAMP") || className.equals("oracle.sql.DATE")) {
                return value.getClass().getMethod("timestampValue").invoke(value);
            }
            if (className.equals("org.postgresql.util.PGobject")) {
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
            return value;
        }
        if (value instanceof java.time.OffsetDateTime) {
            return Timestamp.from(((java.time.OffsetDateTime) value).toInstant());
        }
        if (value instanceof java.time.LocalDateTime) {
            return Timestamp.valueOf((java.time.LocalDateTime) value);
        }
        return value;
    }
}
