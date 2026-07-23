package cn.easyreport.sync.datasync;

/**
 * Converts JDBC driver-specific values before writing them to the target database.
 */
public interface DataSyncValueConverter {

    Object convertColumnValue(Object value, String sourceTypeName);

    Object convertValue(Object value);
}
