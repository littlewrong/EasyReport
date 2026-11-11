package cn.easyreport.sync.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connector/J facade for StarRocks 3.1.5's class-directed JDBC scanner.
 * Native multi-bit BIT values are exposed consistently as unsigned BigInteger values.
 * Binary values use bounded uppercase hexadecimal strings rather than character decoding.
 * TIME values are decoded directly from protocol bytes to retain durations and microseconds.
 * The supplied MySQL URL and connection properties are passed through unchanged.
 */
public class MySqlBitCompatibleDriver implements Driver {
    private static final Pattern BIT_TYPE = Pattern.compile("BIT(?:\\s*\\(\\s*\\d+\\s*\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BINARY_TYPE = Pattern.compile("(?:(?:VAR)?BINARY(?:\\s*\\(\\s*\\d+\\s*\\))?|(?:TINY|MEDIUM|LONG)?BLOB)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_TYPE = Pattern.compile("TIME(?:\\s*\\(\\s*[0-6]\\s*\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_TEXT = Pattern.compile("(-?)([0-9]{2,3}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,6}))?");
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};
    private static final int MAX_HEX_TEXT_LENGTH = 1_048_576;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private volatile Driver delegate;

    static {
        try {
            DriverManager.registerDriver(new MySqlBitCompatibleDriver());
        } catch (SQLException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public MySqlBitCompatibleDriver() {
        // Load Connector/J only when used, so registration itself needs no vendor classes.
    }

    MySqlBitCompatibleDriver(Driver delegate) {
        if (delegate == null) throw new IllegalArgumentException("Delegate driver is required");
        this.delegate = delegate;
    }

    private Driver delegate() throws SQLException {
        Driver current = delegate;
        if (current == null) {
            synchronized (this) {
                current = delegate;
                if (current == null) {
                    try {
                        current = (Driver) Class.forName("com.mysql.cj.jdbc.Driver", true,
                                MySqlBitCompatibleDriver.class.getClassLoader()).getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException | LinkageError failure) {
                        throw new SQLException("MySQL Connector/J driver is unavailable", "08001", failure);
                    }
                    delegate = current;
                }
            }
        }
        return current;
    }

    private static boolean mysqlUrl(String url) {
        return url != null && url.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length());
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!mysqlUrl(url)) return null;
        Connection connection = delegate().connect(url, info);
        return connection == null ? null : wrap(connection, Connection.class, null, null, null);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return mysqlUrl(url) && delegate().acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate().getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        try { return delegate().getMajorVersion(); }
        catch (SQLException failure) { throw new IllegalStateException("MySQL Connector/J driver is unavailable", failure); }
    }

    @Override
    public int getMinorVersion() {
        try { return delegate().getMinorVersion(); }
        catch (SQLException failure) { throw new IllegalStateException("MySQL Connector/J driver is unavailable", failure); }
    }

    @Override
    public boolean jdbcCompliant() {
        try { return delegate().jdbcCompliant(); }
        catch (SQLException failure) { throw new IllegalStateException("MySQL Connector/J driver is unavailable", failure); }
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        try { return delegate().getParentLogger(); }
        catch (SQLFeatureNotSupportedException failure) { throw failure; }
        catch (SQLException failure) { throw new SQLFeatureNotSupportedException("MySQL Connector/J driver is unavailable", failure); }
    }

    private static <T> T wrap(T delegate, Class<T> api, Connection connection, Statement statement,
            CompatibleColumns columns) throws SQLException {
        if (delegate == null) return null;
        JdbcHandler handler = new JdbcHandler(delegate, connection, statement, columns);
        T proxy = api.cast(Proxy.newProxyInstance(MySqlBitCompatibleDriver.class.getClassLoader(),
                new Class<?>[]{api}, handler));
        if (proxy instanceof Connection) handler.connection = (Connection) proxy;
        if (proxy instanceof Statement) handler.statement = (Statement) proxy;
        return proxy;
    }

    private static Statement wrapStatement(Statement statement, Connection connection) throws SQLException {
        if (statement instanceof CallableStatement) return wrap((CallableStatement) statement, CallableStatement.class, connection, null, null);
        if (statement instanceof PreparedStatement) return wrap((PreparedStatement) statement, PreparedStatement.class, connection, null, null);
        return wrap(statement, Statement.class, connection, null, null);
    }

    private static Object call(Object delegate, Method method, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static final class JdbcHandler implements InvocationHandler {
        private final Object delegate;
        private Connection connection;
        private Statement statement;
        private CompatibleColumns columns;

        JdbcHandler(Object delegate, Connection connection, Statement statement, CompatibleColumns columns) {
            this.delegate = delegate;
            this.connection = connection;
            this.statement = statement;
            this.columns = columns;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(name)) return proxy == args[0];
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("toString".equals(name)) return "MySqlBitCompatibleDriver JDBC wrapper";
            }
            if (("unwrap".equals(name) || "isWrapperFor".equals(name)) && args != null && args.length == 1) {
                Class<?> api = (Class<?>) args[0];
                if (api != null && api.isInstance(proxy)) return "unwrap".equals(name) ? proxy : Boolean.TRUE;
                // Vendor interfaces remain available through the delegate's Wrapper contract.
                return call(delegate, method, args);
            }
            if ("getConnection".equals(name) && connection != null) return connection;
            if ("getStatement".equals(name) && statement != null) return statement;

            if (delegate instanceof ResultSetMetaData && "getColumnClassName".equals(name)) {
                int conversion = compatibleColumns().conversion((Integer) args[0]);
                if (conversion == CompatibleColumns.BIT) return BigInteger.class.getName();
                if (conversion == CompatibleColumns.BINARY || conversion == CompatibleColumns.TIME) return String.class.getName();
            }
            if (delegate instanceof ResultSet && "getString".equals(name) && args != null && args.length == 1) {
                ResultSet rows = (ResultSet) delegate;
                int index = args[0] instanceof Integer ? (Integer) args[0] : rows.findColumn((String) args[0]);
                if (compatibleColumns().conversion(index) == CompatibleColumns.TIME) return timeText(rows, index, compatibleColumns().scale(index));
                if (compatibleColumns().conversion(index) == CompatibleColumns.BINARY) return hexadecimal(rows, index);
            }
            if (delegate instanceof ResultSet && "getObject".equals(name) && args != null && args.length >= 1) {
                ResultSet rows = (ResultSet) delegate;
                int index = args[0] instanceof Integer ? (Integer) args[0] : rows.findColumn((String) args[0]);
                int conversion = compatibleColumns().conversion(index);
                if (conversion != CompatibleColumns.NONE) {
                    if (args.length == 1 || (args.length == 2 && (args[1] == null
                            ? Map.class.isAssignableFrom(method.getParameterTypes()[1]) : args[1] instanceof Map))) {
                        return conversion == CompatibleColumns.BIT ? unsigned(rows, index)
                                : conversion == CompatibleColumns.BINARY ? hexadecimal(rows, index)
                                : timeText(rows, index, compatibleColumns().scale(index));
                    }
                    if (args.length == 2 && args[1] instanceof Class) {
                        Class<?> type = (Class<?>) args[1];
                        if (conversion == CompatibleColumns.BIT && numericRequest(type)) return convert(unsigned(rows, index), type);
                        if (conversion == CompatibleColumns.BINARY && (type == String.class || type == Object.class)) return hexadecimal(rows, index);
                        if (conversion == CompatibleColumns.TIME && (type == String.class || type == Object.class)) return timeText(rows, index, compatibleColumns().scale(index));
                    }
                }
            }

            Object value = call(delegate, method, args);
            if (value instanceof ResultSet) return wrap((ResultSet) value, ResultSet.class, connection, statement, null);
            if (value instanceof ResultSetMetaData) {
                ResultSetMetaData metadata = (ResultSetMetaData) value;
                return wrap(metadata, ResultSetMetaData.class, connection, statement, new CompatibleColumns(metadata));
            }
            if (value instanceof Statement) return wrapStatement((Statement) value, connection);
            if (value instanceof DatabaseMetaData) return wrap((DatabaseMetaData) value, DatabaseMetaData.class, connection, statement, null);
            return value;
        }

        private CompatibleColumns compatibleColumns() throws SQLException {
            if (columns == null) {
                ResultSetMetaData metadata = delegate instanceof ResultSet ? ((ResultSet) delegate).getMetaData()
                        : (ResultSetMetaData) delegate;
                columns = new CompatibleColumns(metadata);
            }
            return columns;
        }
    }

    private static BigInteger unsigned(ResultSet rows, int column) throws SQLException {
        byte[] bytes = rows.getBytes(column);
        if (bytes == null) return null;
        return bytes.length == 0 ? BigInteger.ZERO : new BigInteger(1, bytes);
    }

    private static String hexadecimal(ResultSet rows, int column) throws SQLException {
        byte[] bytes = rows.getBytes(column);
        if (bytes == null) return null;
        // Check before allocating the expanded representation; raw getBytes keeps the vendor's
        // existing row-buffer behavior. Source-side OCTET_LENGTH preflight must run before scans.
        if (bytes.length > MAX_HEX_TEXT_LENGTH / 2) {
            throw new SQLException("Binary HEX exceeds the current 1 MiB JDBC import channel capacity", "22001");
        }
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            result[index * 2] = HEX[value >>> 4];
            result[index * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(result);
    }

    private static String timeText(ResultSet rows, int column, int scale) throws SQLException {
        byte[] bytes = rows.getBytes(column);
        if (bytes == null) return null;
        boolean negative;
        long hours;
        int minutes;
        int seconds;
        long micros;
        if (bytes.length == 0) {
            negative = false; hours = 0; minutes = 0; seconds = 0; micros = 0;
        } else if (bytes[0] == 0 || bytes[0] == 1) {
            // BinaryBufferRow/ByteArrayRow getBytes exposes TIME's payload without its length.
            if (bytes.length != 8 && bytes.length != 12) throw invalidTime();
            negative = bytes[0] == 1;
            hours = unsignedLittleEndian(bytes, 1) * 24 + (bytes[5] & 0xFF);
            if ((bytes[5] & 0xFF) > 23) throw invalidTime();
            minutes = bytes[6] & 0xFF;
            seconds = bytes[7] & 0xFF;
            micros = bytes.length == 12 ? unsignedLittleEndian(bytes, 8) : 0;
        } else {
            if (bytes.length < 8 || bytes.length > 17) throw invalidTime();
            for (byte value : bytes) if (value < 32 || value > 126) throw invalidTime();
            Matcher text = TIME_TEXT.matcher(new String(bytes, StandardCharsets.US_ASCII));
            if (!text.matches()) throw invalidTime();
            negative = !text.group(1).isEmpty();
            hours = Integer.parseInt(text.group(2));
            minutes = Integer.parseInt(text.group(3));
            seconds = Integer.parseInt(text.group(4));
            String fraction = text.group(5);
            micros = fraction == null ? 0 : Integer.parseInt(fraction) * POW10[6 - fraction.length()];
        }
        if (hours > 838 || minutes > 59 || seconds > 59 || micros > 999_999
                || micros % POW10[6 - scale] != 0) throw invalidTime();
        StringBuilder result = new StringBuilder(17);
        if (negative) result.append('-');
        if (hours < 10) result.append('0');
        result.append(hours).append(':');
        if (minutes < 10) result.append('0');
        result.append(minutes).append(':');
        if (seconds < 10) result.append('0');
        result.append(seconds);
        if (scale > 0) {
            result.append('.');
            String fraction = Long.toString(micros / POW10[6 - scale]);
            for (int index = fraction.length(); index < scale; index++) result.append('0');
            result.append(fraction);
        }
        return result.toString();
    }

    private static long unsignedLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL) | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16) | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static SQLException invalidTime() {
        return new SQLException("MySQL TIME payload or fractional precision cannot be represented without loss", "22007");
    }

    private static boolean numericRequest(Class<?> type) {
        return type == Object.class || type == Number.class || type == BigInteger.class || type == BigDecimal.class
                || type == String.class || type == Long.class || type == Long.TYPE || type == Integer.class
                || type == Integer.TYPE || type == Short.class || type == Short.TYPE || type == Byte.class || type == Byte.TYPE;
    }

    private static Object convert(BigInteger value, Class<?> type) throws SQLException {
        if (value == null) return null;
        if (type == String.class) return value.toString();
        if (type == BigDecimal.class) return new BigDecimal(value);
        try {
            if (type == Long.class || type == Long.TYPE) return value.longValueExact();
            if (type == Integer.class || type == Integer.TYPE) return value.intValueExact();
            if (type == Short.class || type == Short.TYPE) return value.shortValueExact();
            if (type == Byte.class || type == Byte.TYPE) return value.byteValueExact();
        } catch (ArithmeticException overflow) {
            throw new SQLException("Unsigned BIT value exceeds requested integer range", "22003");
        }
        return value;
    }

    private static final class CompatibleColumns {
        static final int NONE = 0;
        static final int BIT = 1;
        static final int BINARY = 2;
        static final int TIME = 3;
        private final int[] conversions;
        private final int[] scales;

        CompatibleColumns(ResultSetMetaData metadata) throws SQLException {
            conversions = new int[metadata.getColumnCount() + 1];
            scales = new int[conversions.length];
            for (int index = 1; index < conversions.length; index++) {
                String nativeType = metadata.getColumnTypeName(index);
                int jdbcType = metadata.getColumnType(index);
                if (jdbcType == Types.BIT && nativeType != null && BIT_TYPE.matcher(nativeType.trim()).matches()) {
                    int precision = metadata.getPrecision(index);
                    if (precision >= 2 && precision <= 64) conversions[index] = BIT;
                } else if ((jdbcType == Types.BINARY || jdbcType == Types.VARBINARY || jdbcType == Types.LONGVARBINARY || jdbcType == Types.BLOB)
                        && nativeType != null && BINARY_TYPE.matcher(nativeType.trim()).matches()) {
                    conversions[index] = BINARY;
                } else if (jdbcType == Types.TIME && nativeType != null && TIME_TYPE.matcher(nativeType.trim()).matches()) {
                    int scale = metadata.getScale(index);
                    if (scale >= 0 && scale <= 6) { conversions[index] = TIME; scales[index] = scale; }
                }
            }
        }

        int conversion(int index) {
            return index > 0 && index < conversions.length ? conversions[index] : NONE;
        }

        int scale(int index) { return scales[index]; }
    }
}
