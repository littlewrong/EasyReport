package cn.easyreport.sync.datasync;

import java.math.BigDecimal;
import java.math.BigInteger;

/** DM targets use standard JDBC temporal/LOB values and text for foreign driver objects. */
class DmDataSyncValueConverter extends OracleDataSyncValueConverter {
    @Override
    public Object convertColumnValue(Object value, String sourceTypeName) {
        if ("BIT".equalsIgnoreCase(sourceTypeName) && value instanceof Boolean) {
            return Boolean.TRUE.equals(value) ? 1 : 0;
        }
        if ("BIT".equalsIgnoreCase(sourceTypeName) && value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return bytes.length == 0 ? BigDecimal.ZERO : new BigDecimal(new BigInteger(1, bytes));
        }
        return super.convertColumnValue(value, sourceTypeName);
    }

    @Override
    public Object convertValue(Object value) {
        if (value != null) {
            for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
                if ("org.postgresql.util.PGobject".equals(type.getName())) {
                    try {
                        return value.getClass().getMethod("getValue").invoke(value);
                    } catch (ReflectiveOperationException e) {
                        return value.toString();
                    }
                }
            }
        }
        return super.convertValue(value);
    }
}
