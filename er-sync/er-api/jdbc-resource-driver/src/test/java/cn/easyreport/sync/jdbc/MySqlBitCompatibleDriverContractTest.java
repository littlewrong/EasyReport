package cn.easyreport.sync.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/** Offline executable contract tests: no database, vendor JAR, or test framework required. */
public final class MySqlBitCompatibleDriverContractTest {
    public static void main(String[] args) throws Exception {
        numericBitValuesAndMetadataAgree();
        typedAndMappedGetObjectRemainUnsigned();
        statementKindsLifecycleAndExceptionsPassThrough();
        driverAndWrapperContractsPassThrough();
        binaryValuesUseLosslessUppercaseHexAndRespectCapacity();
        textTimePreservesDurationsSignAndMicroseconds();
        binaryTimeDecodesRawPayloadWithoutVendorTemporalConversions();
        invalidTimeAndUnrelatedTypesKeepTheirBoundaries();
        resourceVersionTwoEntryIsDistinctAndKeepsTheConversionContract();
        System.out.println("MySqlBitCompatibleDriver contract tests passed");
    }

    private static void numericBitValuesAndMetadataAgree() throws Exception {
        Fixture f = new Fixture();
        try (Connection connection = f.open(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select fixture")) {
            ResultSetMetaData metadata = rows.getMetaData();
            equal(BigInteger.class.getName(), metadata.getColumnClassName(1), "BIT8 metadata class");
            equal(BigInteger.class.getName(), metadata.getColumnClassName(5), "BIT64 metadata class");
            equal(BigInteger.class.getName(), metadata.getColumnClassName(6), "BIT(n) metadata alias");
            equal(Types.BIT, metadata.getColumnType(1), "SQL BIT type retained");
            equal(8, metadata.getPrecision(1), "BIT width retained");
            equal(Boolean.class.getName(), metadata.getColumnClassName(2), "BIT1 metadata unchanged");
            equal("[B", metadata.getColumnClassName(3), "binary metadata unchanged");
            equal(Integer.class.getName(), metadata.getColumnClassName(4), "integer metadata unchanged");
            equal(Boolean.class.getName(), metadata.getColumnClassName(7), "non-BIT native name unchanged");
            equal(Boolean.class.getName(), metadata.getColumnClassName(8), "unsupported BIT width unchanged");
            int[] values = {0, 1, 127, 128, 170, 255};
            for (int value : values) {
                check(rows.next(), "fixture row exists");
                Object converted = rows.getObject(1);
                equal(BigInteger.valueOf(value), converted, "unsigned BIT8 value");
                equal(BigInteger.class, converted.getClass(), "data class agrees with metadata");
                equal(converted, rows.getObject("bit8"), "label access");
                equal(converted, rows.getObject(6), "BIT(n) alias access");
                equal(Boolean.TRUE, rows.getObject(2), "BIT1 value unchanged");
                check(rows.getObject(3) == f.binary, "binary array identity unchanged");
                equal(Integer.valueOf(42), rows.getObject(4), "integer unchanged");
                equal(Boolean.TRUE, rows.getObject(7), "native BOOLEAN unchanged");
                equal(Boolean.TRUE, rows.getObject(8), "invalid width unchanged");
            }
            check(rows.next(), "NULL fixture row exists");
            equal(null, rows.getObject("bit8"), "SQL NULL remains null");
            check(rows.wasNull(), "getBytes preserves wasNull");
            equal(null, rows.getObject("bit64"), "BIT64 NULL remains null");
            check(!rows.next(), "fixture exhausted");
        }
        check(f.rowsClosed && f.statementClosed && f.connectionClosed, "try-with-resources closes every delegate");
    }

    private static void typedAndMappedGetObjectRemainUnsigned() throws Exception {
        Fixture f = new Fixture();
        try (Connection connection = f.open(); PreparedStatement statement = connection.prepareStatement("select fixture");
                ResultSet rows = statement.executeQuery()) {
            check(rows.next(), "first row");
            BigInteger maximum = new BigInteger("18446744073709551615");
            equal(maximum, rows.getObject("bit64"), "BIT64 unsigned maximum");
            equal(maximum, rows.getObject(5, BigInteger.class), "typed BigInteger");
            equal(maximum, rows.getObject("bit64", Object.class), "typed Object");
            equal(maximum, rows.getObject(5, Number.class), "typed Number");
            equal(maximum.toString(), rows.getObject(5, String.class), "typed unsigned decimal String");
            equal(new BigDecimal(maximum), rows.getObject("bit64", BigDecimal.class), "typed BigDecimal");
            equal(maximum, rows.getObject(5, Collections.<String, Class<?>>emptyMap()), "mapped index");
            equal(maximum, rows.getObject("bit64", (Map<String, Class<?>>) null), "mapped label and null map");
            expectSql("22003", () -> rows.getObject(5, Long.class));
            equal(Boolean.TRUE, rows.getObject(2, Boolean.class), "typed BIT1 delegates");
            check(rows.getObject(3, byte[].class) == f.binary, "typed binary delegates");
            check(rows.next(), "second row");
            equal(new BigInteger("9223372036854775808"), rows.getObject(5), "BIT64 sign boundary");
            check(rows.next(), "third row");
            equal(BigInteger.valueOf(256), rows.getObject(5), "big-endian bytes");
            check(rows.next(), "fourth row");
            equal(BigInteger.ZERO, rows.getObject(5), "empty byte array normalized to zero");
            equal(Long.valueOf(128), rows.getObject(1, Long.class), "typed Long within range");
            expectSql("22003", () -> rows.getObject(1, Byte.class));
            while (rows.next()) { /* Move to SQL NULL, after checking explicit numeric conversions. */ }
        }
    }

    private static void statementKindsLifecycleAndExceptionsPassThrough() throws Exception {
        Fixture f = new Fixture();
        Connection connection = f.open();
        connection.setAutoCommit(false);
        check(!connection.getAutoCommit(), "connection properties pass through");
        Statement statement = connection.createStatement();
        check(statement.getConnection() == connection, "Statement owner connection");
        check(statement.execute("select fixture"), "Statement.execute passes through");
        try (ResultSet executed = statement.getResultSet(); ResultSet generated = statement.getGeneratedKeys()) {
            check(executed.next() && generated.next(), "all ResultSet accessors are wrapped");
            equal(BigInteger.ZERO, executed.getObject(1), "getResultSet conversion");
            equal(BigInteger.ZERO, generated.getObject(1), "generated keys conversion");
        }
        ResultSet rows = statement.executeQuery("select fixture");
        check(rows.getStatement() == statement, "ResultSet owner statement");
        statement.cancel();
        check(f.cancelled, "cancel passes through");
        try { statement.executeQuery("fail"); throw new AssertionError("Expected delegate SQL exception"); }
        catch (SQLException expected) { check(expected == f.queryFailure, "InvocationTargetException is unwrapped"); }
        try { rows.getObject("missing"); throw new AssertionError("Expected unknown label"); }
        catch (SQLException expected) { equal("S0022", expected.getSQLState(), "findColumn failure preserved"); }
        PreparedStatement prepared = connection.prepareStatement("select fixture");
        prepared.setInt(1, 7);
        check(f.parameterSet, "prepared parameters pass through");
        equal(BigInteger.class.getName(), prepared.getMetaData().getColumnClassName(1), "prepared metadata wrapped");
        ResultSet preparedRows = prepared.executeQuery();
        check(preparedRows.getStatement() == prepared, "PreparedStatement result owner");
        preparedRows.close(); prepared.close();
        CallableStatement callable = connection.prepareCall("call fixture");
        callable.registerOutParameter(1, Types.INTEGER);
        equal(Integer.valueOf(42), callable.getObject(1), "Callable OUT parameters remain delegated");
        ResultSet callableRows = callable.executeQuery();
        check(callableRows.next(), "callable row");
        equal(BigInteger.ZERO, callableRows.getObject(1), "callable ResultSet wrapped");
        callableRows.close(); callable.close();
        rows.close(); statement.close(); connection.close();
        check(f.rowsClosed && f.statementClosed && f.connectionClosed, "explicit close delegates");
    }

    private static void driverAndWrapperContractsPassThrough() throws Exception {
        Fixture f = new Fixture();
        Properties properties = new Properties(); properties.setProperty("user", "fixture_user");
        Connection connection = f.driver.connect("jdbc:mysql://fixture.invalid:3306/example", properties);
        check(f.seenProperties == properties, "properties passed without copying or mutation");
        equal("jdbc:mysql://fixture.invalid:3306/example", f.seenUrl, "JDBC URL passed unchanged");
        check(f.driver.acceptsURL(f.seenUrl), "real MySQL protocol accepted");
        check(!f.driver.acceptsURL("jdbc:postgresql://fixture.invalid/example"), "other protocols rejected");
        equal(null, f.driver.connect("jdbc:postgresql://fixture.invalid/example", properties), "foreign connect returns null");
        equal(8, f.driver.getMajorVersion(), "major version delegates");
        equal(33, f.driver.getMinorVersion(), "minor version delegates");
        check(f.driver.jdbcCompliant(), "compliance delegates");
        check(f.driver.getParentLogger() == f.logger, "logger delegates");
        check(f.driver.getPropertyInfo(f.seenUrl, properties) == f.propertyInfo, "property info delegates");
        check(connection.unwrap(Connection.class) == connection, "unwrap standard API retains adaptation");
        check(connection.isWrapperFor(Connection.class), "standard Wrapper contract");
        check(connection.unwrap(VendorConnection.class) == f.nativeConnection, "vendor unwrap delegates");
        check(connection.isWrapperFor(VendorConnection.class), "vendor wrapper check delegates");
        expectSql("S1009", () -> connection.unwrap(String.class));
        DatabaseMetaData database = connection.getMetaData();
        check(database.getConnection() == connection, "database metadata owner connection");
        ResultSet rows = connection.createStatement().executeQuery("select fixture");
        check(rows.unwrap(ResultSet.class) == rows, "ResultSet unwrap retains adaptation");
        ResultSetMetaData metadata = rows.getMetaData();
        check(metadata.unwrap(ResultSetMetaData.class) == metadata, "metadata unwrap retains adaptation");
        check(connection.equals(connection) && !connection.equals(f.nativeConnection), "proxy equality is consistent");
        check(!connection.toString().contains("fixture.invalid"), "wrapper diagnostics do not expose URL");
        rows.close(); connection.close();
    }

    private static void binaryValuesUseLosslessUppercaseHexAndRespectCapacity() throws Exception {
        Object[][] types = {{Types.BINARY, "BINARY(16)"}, {Types.VARBINARY, "VARBINARY(32)"},
                {Types.LONGVARBINARY, "MEDIUMBLOB"}, {Types.BLOB, "BLOB"},
                {Types.LONGVARBINARY, "tinyblob"}, {Types.LONGVARBINARY, "LONGBLOB"}};
        byte[] padded = new byte[16]; padded[1] = (byte) 0xFF; padded[2] = (byte) 0x80;
        padded[3] = (byte) 0xC3; padded[4] = 0x28;
        for (Object[] type : types) {
            TypeFixture f = new TypeFixture((Integer) type[0], (String) type[1], "[B", 0,
                    new Object[]{padded, new byte[]{0, (byte) 0xFF}, new byte[0], null});
            try (Connection connection = f.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
                equal(String.class.getName(), rows.getMetaData().getColumnClassName(1), "binary metadata is String");
                check(rows.next(), "padded binary");
                String expected = "00FF80C3280000000000000000000000";
                equal(expected, rows.getObject(1), "invalid UTF8, leading zeros and BINARY padding retained");
                equal(expected, rows.getString(1), "binary getString agrees with converted String value");
                equal(expected, rows.getObject("value", String.class), "binary typed String");
                equal(expected, rows.getObject(1, Object.class), "binary typed Object");
                equal(expected, rows.getObject(1, Collections.<String, Class<?>>emptyMap()), "binary map access");
                check(rows.getBytes(1) == padded, "explicit getBytes remains raw");
                check(rows.next(), "two binary octets");
                equal("00FF", rows.getObject("value"), "uppercase zero and FF");
                check(rows.next(), "empty binary"); equal("", rows.getObject(1), "empty binary is empty HEX");
                check(rows.next(), "NULL binary"); equal(null, rows.getObject(1), "NULL binary remains NULL");
                check(rows.wasNull(), "binary NULL wasNull");
                equal(0, f.objectsRead, "binary conversion never calls vendor getObject");
                equal(0, f.stringsRead, "binary conversion never decodes bytes as text");
            }
        }
        TypeFixture boundary = new TypeFixture(Types.BLOB, "LONGBLOB", "java.sql.Blob", 0,
                new Object[]{fill(524_288, (byte) 0xAB), fill(524_289, (byte) 0xFF)});
        try (Connection connection = boundary.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
            check(rows.next(), "maximum HEX channel input");
            String result = (String) rows.getObject(1);
            equal(1_048_576, result.length(), "one MiB HEX boundary");
            check(result.startsWith("ABAB") && result.endsWith("ABAB"), "boundary HEX is not truncated");
            check(rows.next(), "overlarge binary input");
            expectSql("22001", () -> rows.getObject(1));
        }
    }

    private static void textTimePreservesDurationsSignAndMicroseconds() throws Exception {
        String[] values = {"00:00:00", "12:34:56.123456", "-00:30:00.000001", "-25:02:03.123456",
                "100:01:02.000000", "838:59:59.000000", "-838:59:59.000000", null};
        String[] expected = {"00:00:00.000000", "12:34:56.123456", "-00:30:00.000001", "-25:02:03.123456",
                "100:01:02.000000", "838:59:59.000000", "-838:59:59.000000", null};
        Object[] bytes = new Object[values.length];
        for (int index = 0; index < values.length; index++) bytes[index] = ascii(values[index]);
        TypeFixture f = new TypeFixture(Types.TIME, "TIME(6)", "java.sql.Time", 6, bytes);
        try (Connection connection = f.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
            equal(String.class.getName(), rows.getMetaData().getColumnClassName(1), "TIME metadata String");
            for (String value : expected) {
                check(rows.next(), "TIME row");
                equal(value, rows.getObject(1), "TIME getObject");
                equal(value, rows.getObject("value", Object.class), "TIME typed Object");
                equal(value, rows.getObject(1, String.class), "TIME typed String");
                equal(value, rows.getString("value"), "TIME direct getString uses the same codec");
                equal(value, rows.getObject(1, Collections.<String, Class<?>>emptyMap()), "TIME map access");
                if (value == null) check(rows.wasNull(), "TIME NULL wasNull");
            }
            equal(0, f.objectsRead, "TIME never calls lossy vendor getObject");
            equal(0, f.stringsRead, "TIME never calls lossy vendor getString");
            equal(0, f.timesRead, "TIME never calls vendor getTime");
        }
        TypeFixture milliseconds = new TypeFixture(Types.TIME, "TIME(3)", "java.sql.Time", 3,
                new Object[]{ascii("-00:00:00.001"), ascii("25:00:00"), ascii("12:00:00.123000")});
        try (Connection connection = milliseconds.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
            check(rows.next(), "negative fractional zero hour"); equal("-00:00:00.001", rows.getObject(1), "negative millisecond sign");
            check(rows.next(), "duration without source fraction"); equal("25:00:00.000", rows.getObject(1), "metadata scale padding");
            check(rows.next(), "extra zero source fraction"); equal("12:00:00.123", rows.getObject(1), "only zero precision padding removed");
        }
    }

    private static void binaryTimeDecodesRawPayloadWithoutVendorTemporalConversions() throws Exception {
        Object[] values = {new byte[0], timePayload(true, 0, 0, 30, 0, -1),
                timePayload(true, 1, 1, 2, 3, -1), timePayload(false, 4, 4, 1, 2, 123_456),
                timePayload(true, 34, 22, 59, 59, 999_999), timePayload(false, 0, 0, 0, 0, 1), null};
        String[] expected = {"00:00:00.000000", "-00:30:00.000000", "-25:02:03.000000",
                "100:01:02.123456", "-838:59:59.999999", "00:00:00.000001", null};
        TypeFixture f = new TypeFixture(Types.TIME, "TIME", "java.sql.Time", 6, values);
        try (Connection connection = f.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
            for (String value : expected) {
                check(rows.next(), "binary TIME row");
                equal(value, rows.getObject("value"), "binary TIME payload decoded");
                equal(value, rows.getString(1), "binary TIME String agrees");
            }
            equal(0, f.objectsRead + f.stringsRead + f.timesRead, "binary TIME bypasses lossy vendor conversions");
        }
        TypeFixture seconds = new TypeFixture(Types.TIME, "time(0)", "java.sql.Time", 0,
                new Object[]{timePayload(true, 0, 2, 3, 4, -1)});
        try (Connection connection = seconds.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
            check(rows.next(), "scale-zero binary TIME"); equal("-02:03:04", rows.getObject(1), "zero scale has no fraction");
        }
    }

    private static void invalidTimeAndUnrelatedTypesKeepTheirBoundaries() throws Exception {
        Object[] invalid = {ascii("839:00:00"), ascii("12:60:00"), ascii("00:00:60"), ascii("01:02:03.1234567"),
                ascii("not-a-time"), new byte[]{(byte) 0xFF, 1, 2, 3, 4, 5, 6, 7},
                new byte[]{0, 0, 0}, timePayload(false, 0, 24, 0, 0, -1),
                timePayload(false, 0, 0, 0, 0, 1_000_000), timePayload(false, 35, 0, 0, 0, -1)};
        TypeFixture f = new TypeFixture(Types.TIME, "TIME", "java.sql.Time", 6, invalid);
        try (Connection connection = f.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
            for (Object ignored : invalid) { check(rows.next(), "invalid TIME row"); expectSql("22007", () -> rows.getObject(1)); }
        }
        TypeFixture precision = new TypeFixture(Types.TIME, "TIME", "java.sql.Time", 3,
                new Object[]{ascii("00:00:00.123456"), timePayload(false, 0, 0, 0, 0, 1)});
        try (Connection connection = precision.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
            check(rows.next(), "text precision mismatch"); expectSql("22007", () -> rows.getString(1));
            check(rows.next(), "binary precision mismatch"); expectSql("22007", () -> rows.getObject(1));
        }
        Object[][] unrelated = {{Types.CLOB, "CLOB", "java.sql.Clob", "clob-value"},
                {Types.LONGVARCHAR, "JSON", String.class.getName(), "{\"value\":1}"},
                {Types.VARCHAR, "VARCHAR", String.class.getName(), "unchanged"},
                {Types.BLOB, "TEXT", String.class.getName(), "text-not-binary"},
                {Types.TIME_WITH_TIMEZONE, "TIME WITH TIME ZONE", String.class.getName(), "12:00:00+08"}};
        for (Object[] item : unrelated) {
            TypeFixture unchanged = new TypeFixture((Integer) item[0], (String) item[1], (String) item[2], 0, new Object[]{item[3]});
            unchanged.forbidVendorReads = false;
            try (Connection connection = unchanged.open(); ResultSet rows = connection.createStatement().executeQuery("fixture")) {
                equal(item[2], rows.getMetaData().getColumnClassName(1), "unrelated metadata unchanged");
                check(rows.next(), "unrelated value row"); equal(item[3], rows.getObject(1), "unrelated data unchanged");
            }
        }
    }

    private static void resourceVersionTwoEntryIsDistinctAndKeepsTheConversionContract() throws Exception {
        Class<?> entry = Class.forName("cn.easyreport.sync.jdbc.MySqlResourceCompatibleDriver");
        check(entry != MySqlBitCompatibleDriver.class && Driver.class.isAssignableFrom(entry), "v2 has a distinct JDBC driver class");
        Driver driver = (Driver) entry.getConstructor().newInstance();
        check(!driver.acceptsURL("jdbc:postgresql://fixture.invalid/example"), "v2 default constructor needs no vendor classes for registration");
        TypeFixture f = new TypeFixture(Types.BINARY, "BINARY(16)", "[B", 0, new Object[]{new byte[]{0, (byte) 0xFF}});
        Driver injected = new MySqlResourceCompatibleDriver(f.driver);
        try (Connection connection = injected.connect("jdbc:mysql://fixture.invalid/example", new Properties());
                ResultSet rows = connection.createStatement().executeQuery("fixture")) {
            check(rows.next(), "v2 entry row");
            equal(String.class.getName(), rows.getMetaData().getColumnClassName(1), "v2 entry metadata");
            equal("00FF", rows.getObject(1), "v2 entry data");
        }
    }

    private static byte[] ascii(String text) { return text == null ? null : text.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] timePayload(boolean negative, long days, int hours, int minutes, int seconds, int micros) {
        byte[] bytes = new byte[micros < 0 ? 8 : 12]; bytes[0] = (byte) (negative ? 1 : 0);
        for (int index = 0; index < 4; index++) bytes[index + 1] = (byte) (days >>> (8 * index));
        bytes[5] = (byte) hours; bytes[6] = (byte) minutes; bytes[7] = (byte) seconds;
        if (micros >= 0) for (int index = 0; index < 4; index++) bytes[index + 8] = (byte) (micros >>> (8 * index));
        return bytes;
    }

    public interface VendorConnection { }

    private static class Fixture {
        final Logger logger = Logger.getLogger("bit-contract");
        final DriverPropertyInfo[] propertyInfo = {new DriverPropertyInfo("fixture", "value")};
        final SQLException queryFailure = new SQLException("fixture query failure", "42000", 17);
        final byte[] binary = {(byte) 0x80, (byte) 0xFF};
        final MySqlBitCompatibleDriver driver;
        Connection nativeConnection;
        boolean connectionClosed, statementClosed, rowsClosed, cancelled, parameterSet;
        boolean autoCommit = true;
        String seenUrl;
        Properties seenProperties;

        Fixture() {
            driver = new MySqlBitCompatibleDriver(new Driver() {
                public Connection connect(String url, Properties info) {
                    seenUrl = url; seenProperties = info;
                    nativeConnection = proxy(Connection.class, Fixture.this::connection, VendorConnection.class);
                    return nativeConnection;
                }
                public boolean acceptsURL(String url) { return url.startsWith("jdbc:mysql:"); }
                public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return propertyInfo; }
                public int getMajorVersion() { return 8; }
                public int getMinorVersion() { return 33; }
                public boolean jdbcCompliant() { return true; }
                public Logger getParentLogger() { return logger; }
            });
        }

        Connection open() throws SQLException { return driver.connect("jdbc:mysql://fixture.invalid/example", new Properties()); }

        Object connection(Object proxy, Method method, Object[] args) throws Throwable {
            Object wrapper = wrapper(proxy, method, args);
            if (wrapper != UNHANDLED) return wrapper;
            switch (method.getName()) {
                case "createStatement": return statement(Statement.class);
                case "prepareStatement": return statement(PreparedStatement.class);
                case "prepareCall": return statement(CallableStatement.class);
                case "close": connectionClosed = true; return null;
                case "setAutoCommit": autoCommit = (Boolean) args[0]; return null;
                case "getAutoCommit": return autoCommit;
                case "getMetaData": return proxy(DatabaseMetaData.class, (p, m, a) -> {
                    if ("getConnection".equals(m.getName())) return nativeConnection;
                    throw new AssertionError(m.getName());
                });
                default: throw new AssertionError(method.getName());
            }
        }

        <T extends Statement> T statement(Class<T> api) {
            return proxy(api, (p, m, a) -> {
                Object wrapper = wrapper(p, m, a);
                if (wrapper != UNHANDLED) return wrapper;
                switch (m.getName()) {
                    case "executeQuery":
                        if (a != null && a.length > 0 && "fail".equals(a[0])) throw queryFailure;
                        return rows((Statement) p);
                    case "execute": return true;
                    case "getResultSet": case "getGeneratedKeys": return rows((Statement) p);
                    case "getMetaData": return metadata();
                    case "getConnection": return nativeConnection;
                    case "cancel": cancelled = true; return null;
                    case "close": statementClosed = true; return null;
                    case "setInt": parameterSet = true; return null;
                    case "registerOutParameter": return null;
                    case "getObject": return Integer.valueOf(42);
                    default: throw new AssertionError(m.getName());
                }
            });
        }

        ResultSet rows(Statement owner) {
            int[] cursor = {-1};
            boolean[] wasNull = {false};
            byte[][] longs = {fill(8, (byte) 0xFF), {(byte) 0x80, 0, 0, 0, 0, 0, 0, 0}, {1, 0}, {}, null, {1}, null};
            Integer[] small = {0, 1, 127, 128, 170, 255, null};
            return proxy(ResultSet.class, (p, m, a) -> {
                Object wrapper = wrapper(p, m, a);
                if (wrapper != UNHANDLED) return wrapper;
                switch (m.getName()) {
                    case "getMetaData": return metadata();
                    case "next": return ++cursor[0] < small.length;
                    case "close": rowsClosed = true; return null;
                    case "getStatement": return owner;
                    case "wasNull": return wasNull[0];
                    case "findColumn": return column((String) a[0]);
                    case "getBytes":
                        int bytesIndex = (Integer) a[0];
                        check(bytesIndex == 1 || bytesIndex == 5 || bytesIndex == 6, "only multi-bit BIT uses getBytes");
                        byte[] bytes = bytesIndex == 5 ? longs[cursor[0]] : small[cursor[0]] == null
                                ? null : new byte[]{(byte) (int) small[cursor[0]]};
                        wasNull[0] = bytes == null;
                        return bytes;
                    case "getObject":
                        int index = a[0] instanceof Integer ? (Integer) a[0] : column((String) a[0]);
                        Object result = index == 3 ? binary : index == 4 ? Integer.valueOf(42)
                                : index == 2 || index == 7 || index == 8 ? Boolean.TRUE
                                : index == 5 ? longs[cursor[0]] : new byte[]{(byte) (int) small[cursor[0]]};
                        wasNull[0] = result == null;
                        return result;
                    default: throw new AssertionError(m.getName());
                }
            });
        }

        ResultSetMetaData metadata() {
            return proxy(ResultSetMetaData.class, (p, m, a) -> {
                Object wrapper = wrapper(p, m, a);
                if (wrapper != UNHANDLED) return wrapper;
                if ("getColumnCount".equals(m.getName())) return 8;
                int index = (Integer) a[0];
                switch (m.getName()) {
                    case "getColumnType": return index == 3 ? Types.VARBINARY : index == 4 ? Types.INTEGER : Types.BIT;
                    case "getColumnTypeName": return index == 3 ? "BIT" : index == 4 ? "INT"
                            : index == 6 ? " bit(8) " : index == 7 ? "BOOLEAN" : "BIT";
                    case "getPrecision": return index == 2 ? 1 : index == 5 ? 64 : index == 8 ? 65 : 8;
                    case "getColumnClassName": return index == 3 ? "[B" : index == 4 ? Integer.class.getName() : Boolean.class.getName();
                    default: throw new AssertionError(m.getName());
                }
            });
        }
    }

    private static final class TypeFixture extends Fixture {
        final int jdbcType;
        final String nativeType;
        final String javaClass;
        final int scale;
        final Object[] values;
        boolean forbidVendorReads = true;
        int objectsRead, stringsRead, timesRead;

        TypeFixture(int jdbcType, String nativeType, String javaClass, int scale, Object[] values) {
            this.jdbcType = jdbcType; this.nativeType = nativeType; this.javaClass = javaClass; this.scale = scale; this.values = values;
        }

        @Override ResultSetMetaData metadata() {
            return proxy(ResultSetMetaData.class, (p, m, a) -> {
                Object wrapper = wrapper(p, m, a);
                if (wrapper != UNHANDLED) return wrapper;
                switch (m.getName()) {
                    case "getColumnCount": return 1;
                    case "getColumnType": return jdbcType;
                    case "getColumnTypeName": return nativeType;
                    case "getColumnClassName": return javaClass;
                    case "getPrecision": return 16;
                    case "getScale": return scale;
                    default: throw new AssertionError(m.getName());
                }
            });
        }

        @Override ResultSet rows(Statement owner) {
            int[] cursor = {-1}; boolean[] wasNull = {false};
            return proxy(ResultSet.class, (p, m, a) -> {
                Object wrapper = wrapper(p, m, a);
                if (wrapper != UNHANDLED) return wrapper;
                switch (m.getName()) {
                    case "getMetaData": return metadata();
                    case "next": return ++cursor[0] < values.length;
                    case "getStatement": return owner;
                    case "findColumn": if ("value".equals(a[0])) return 1; throw new SQLException("Unknown fixture column", "S0022");
                    case "close": rowsClosed = true; return null;
                    case "wasNull": return wasNull[0];
                    case "getBytes": wasNull[0] = values[cursor[0]] == null; return values[cursor[0]];
                    case "getObject": objectsRead++; check(!forbidVendorReads, "vendor getObject must be bypassed"); break;
                    case "getString": stringsRead++; check(!forbidVendorReads, "vendor getString must be bypassed"); break;
                    case "getTime": timesRead++; throw new AssertionError("vendor getTime must be bypassed");
                    default: throw new AssertionError(m.getName());
                }
                wasNull[0] = values[cursor[0]] == null;
                return values[cursor[0]];
            });
        }
    }

    private static int column(String name) throws SQLException {
        String[] names = {"bit8", "bit1", "binary", "integer", "bit64", "bitNamed", "notBitName", "invalidWidth"};
        for (int index = 0; index < names.length; index++) if (names[index].equals(name)) return index + 1;
        throw new SQLException("Unknown fixture column", "S0022");
    }

    private static byte[] fill(int length, byte value) { byte[] bytes = new byte[length]; Arrays.fill(bytes, value); return bytes; }
    private static final Object UNHANDLED = new Object();

    private static Object wrapper(Object proxy, Method method, Object[] args) throws SQLException {
        if ("unwrap".equals(method.getName())) {
            if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
            throw new SQLException("Unsupported fixture unwrap", "S1009");
        }
        if ("isWrapperFor".equals(method.getName())) return ((Class<?>) args[0]).isInstance(proxy);
        return UNHANDLED;
    }

    private static <T> T proxy(Class<T> api, InvocationHandler handler, Class<?>... more) {
        Class<?>[] interfaces = new Class<?>[more.length + 1]; interfaces[0] = api;
        System.arraycopy(more, 0, interfaces, 1, more.length);
        return api.cast(Proxy.newProxyInstance(MySqlBitCompatibleDriverContractTest.class.getClassLoader(), interfaces, handler));
    }

    private interface SqlCall { Object call() throws SQLException; }
    private static void expectSql(String state, SqlCall call) throws SQLException {
        try { call.call(); throw new AssertionError("Expected SQL exception"); }
        catch (SQLException expected) { equal(state, expected.getSQLState(), "SQL state"); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(message);
    }
}
