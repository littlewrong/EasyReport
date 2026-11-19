package cn.easyreport.sync.datasync;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class StarRocksBinaryTextTransferTest {
    @Test
    public void mysqlAndTidbBinaryFamiliesPreserveQrTextNullEmptyAndUnicode() throws Exception {
        for (String sourceType : Arrays.asList("MYSQL", "TIDB")) {
            for (int type : new int[]{Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY}) {
                Fixture f = new Fixture(type, "STRING");
                f.sourceType = sourceType;
                StarRocksBinaryTextTransfer transfer = f.prepare();
                assertTrue(transfer.hasTextColumns());
                assertTrue(transfer.contains("QR"));
                String qr = "https://发票.test/二维码?金额=10.2&emoji=\uD83D\uDE00\u0000";
                assertEquals(qr, transfer.read(f.row(qr.getBytes(StandardCharsets.UTF_8)), "qr"));
                assertEquals("", transfer.read(f.row(new byte[0]), "qr"));
                assertNull(transfer.read(f.row(null), "qr"));
                assertEquals(23, transfer.read(f.row(23), "id"));
                assertFalse(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("CREATE") || sql.startsWith("ALTER")));
            }
        }
    }

    @Test
    public void bitMetadataAndNativeBinaryTargetStayOutOfTextConversion() throws Exception {
        Fixture bit = new Fixture(Types.BIT, "STRING");
        StarRocksBinaryTextTransfer bitTransfer = bit.prepare();
        assertFalse(bitTransfer.hasTextColumns());
        assertEquals(0, bit.targetReads);
        byte[] bits = new byte[]{(byte) 0xff};
        assertSame(bits, bitTransfer.read(bit.row(bits), "qr"));

        Fixture binary = new Fixture(Types.BLOB, "VARBINARY(255)");
        StarRocksBinaryTextTransfer binaryTransfer = binary.prepare();
        assertFalse(binaryTransfer.hasTextColumns());
        assertSame(bits, binaryTransfer.read(binary.row(bits), "qr"));
    }

    @Test
    public void missingNonBlobTargetColumnFailsBeforeAnyTransfer() throws Exception {
        Fixture f = new Fixture(Types.BLOB, "STRING");
        f.missingScalarTarget = true;
        try { f.prepare(); fail("Expected missing scalar target rejection"); }
        catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("缺少目标字段 id"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("尚未清空目标表"));
        }
        assertFalse(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("TRUNCATE") || sql.startsWith("INSERT")));
    }

    @Test
    public void sourceAndTargetMetadataDriverFailuresAreSanitized() throws Exception {
        for (boolean source : new boolean[]{true, false}) {
            Fixture f = new Fixture(Types.BLOB, "STRING");
            SQLException failure = new SQLException("secret QR payload password=hidden", "08001", 9);
            if (source) f.metadataFailure = failure;
            else f.targetMetadataFailure = failure;
            try { f.prepare(); fail("Expected metadata failure"); }
            catch (SQLException expected) {
                assertSanitized(expected);
                assertTrue(expected.getMessage().contains("尚未清空目标表"));
                assertEquals("08001", expected.getSQLState());
                assertEquals(9, expected.getErrorCode());
            }
        }
    }

    @Test
    public void capacityIsUtf8BytesAndInvalidSequencesNeverBecomeReplacementText() throws Exception {
        Fixture f = new Fixture(Types.BLOB, "VARCHAR(6)");
        StarRocksBinaryTextTransfer transfer = f.prepare();
        assertEquals("二维码".substring(0, 2), transfer.read(f.row("二维码".substring(0, 2).getBytes(StandardCharsets.UTF_8)), "qr"));
        rejectRead(transfer, f.row("二维码".getBytes(StandardCharsets.UTF_8)), "容量", "22001");
        rejectRead(transfer, f.row(new byte[]{(byte) 0xc3, 0x28}), "UTF-8", "22021");
        rejectRead(transfer, f.row(new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}), "UTF-8", "22021");
        assertEquals("", transfer.read(f.row(new byte[0]), "qr"));
    }

    @Test
    public void blobStreamsAreClosedAndFreedOnSuccessDecodeFailureAndCapacityFailure() throws Exception {
        Fixture f = new Fixture(Types.BLOB, "CHAR(6)");
        StarRocksBinaryTextTransfer transfer = f.prepare();
        for (byte[] bytes : Arrays.asList("发票".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xff}, new byte[7])) {
            BlobValue blob = new BlobValue(bytes, bytes.length);
            try {
                Object actual = transfer.read(f.row(blob.proxy()), "qr");
                assertEquals("发票", actual);
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("UTF-8") || expected.getMessage().contains("容量"));
            }
            assertTrue(blob.freed);
            assertTrue(blob.closed || bytes.length > 6); // Known oversize LOBs do not need to open a stream.
        }
        BlobValue changed = new BlobValue(new byte[100], 1);
        rejectRead(transfer, f.row(changed.proxy()), "容量", "22001");
        assertEquals(7, changed.readBytes);
        assertTrue(changed.closed);
        assertTrue(changed.freed);
    }

    @Test
    public void blobReleaseFailuresStaySecondaryAndAllDriverDiagnosticsAreSanitized() throws Exception {
        Fixture f = new Fixture(Types.BLOB, "CHAR(6)");
        StarRocksBinaryTextTransfer transfer = f.prepare();
        for (byte[] bytes : Arrays.asList(new byte[]{(byte) 0xff}, new byte[7])) {
            BlobValue blob = new BlobValue(bytes, bytes.length);
            blob.freeFailure = new SQLException("secret QR payload password=hidden", "HY010", 8);
            try { transfer.read(f.row(blob.proxy()), "qr"); fail("Expected primary data failure"); }
            catch (SQLException expected) {
                assertEquals(bytes.length > 6 ? "22001" : "22021", expected.getSQLState());
                assertEquals(1, expected.getSuppressed().length);
                assertSanitized(expected);
            }
            assertTrue(blob.freed);
        }
        for (String stage : Arrays.asList("length", "stream", "free", "getObject")) {
            BlobValue blob = new BlobValue(new byte[0], 0);
            SQLException failure = new SQLException("secret QR payload password=hidden", "HY000", 4);
            if ("length".equals(stage)) blob.lengthFailure = failure;
            if ("stream".equals(stage)) blob.streamFailure = failure;
            if ("free".equals(stage)) blob.freeFailure = failure;
            ResultSet row = "getObject".equals(stage) ? proxy(ResultSet.class, (name, args) -> { throw failure; }) : f.row(blob.proxy());
            try { transfer.read(row, "qr"); fail("Expected sanitized " + stage + " failure"); }
            catch (SQLException expected) {
                assertSanitized(expected);
                assertEquals("HY000", expected.getSQLState());
                assertEquals(4, expected.getErrorCode());
            }
        }
    }

    @Test
    public void preflightStreamsAllEffectiveRowsBeforeAnyTargetMutation() throws Exception {
        Fixture f = new Fixture(Types.LONGVARBINARY, "STRING");
        StarRocksBinaryTextTransfer transfer = f.prepare();
        f.values = Arrays.asList(null, new byte[0], "合法".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xff});
        try {
            transfer.preflight(f.source(), "erp_base", "tb_base_e_invoice", "updated_at", null);
            fail("Invalid last row must fail preflight");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("尚未清空目标表"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("UTF-8"));
        }
        assertEquals(Integer.MIN_VALUE, f.fetchSize);
        assertTrue(f.queries.contains("SELECT `qr` FROM `erp_base`.`tb_base_e_invoice` WHERE `updated_at` IS NOT NULL"));
        assertEquals(1, f.targetReads);
        assertFalse(f.queries.toString(), f.queries.stream().anyMatch(sql -> sql.startsWith("TRUNCATE") || sql.startsWith("INSERT")));
        assertTrue(f.preflightClosed);
    }

    @Test
    public void oneMibIsAcceptedAndOneExtraByteIsRejectedAtReadAndPreflight() throws Exception {
        Fixture f = new Fixture(Types.BLOB, "STRING");
        StarRocksBinaryTextTransfer transfer = f.prepare();
        byte[] boundary = new byte[1_048_576];
        Arrays.fill(boundary, (byte) 'a');
        assertEquals(boundary.length, ((String) transfer.read(f.row(boundary), "qr")).length());
        rejectRead(transfer, f.row(new byte[boundary.length + 1]), "容量", "22001");
        f.values = Collections.singletonList(new byte[boundary.length + 1]);
        try {
            transfer.preflight(f.source(), "erp_base", "tb_base_e_invoice", null, null);
            fail("Expected preflight byte capacity failure");
        } catch (SQLException expected) {
            assertEquals("22001", expected.getSQLState());
            assertTrue(expected.getMessage().contains("尚未清空目标表"));
        }
    }

    @Test
    public void oracleBlobAndRawUseOwnerQualifiedSqlAndPositiveFetchSize() throws Exception {
        for (int type : new int[]{Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY}) {
            Fixture f = new Fixture(type, "STRING");
            f.sourceType = "ORACLE";
            StarRocksBinaryTextTransfer transfer = f.prepare();
            f.values = Collections.singletonList("发票原文".getBytes(StandardCharsets.UTF_8));
            transfer.preflight(f.source(), "erp_base", "tb_base_e_invoice", "UPDATED_AT", null);
            assertTrue(f.queries.toString(), f.queries.contains("SELECT \"qr\", \"id\" FROM \"ERP_BASE\".\"TB_BASE_E_INVOICE\" WHERE 1=0"));
            assertTrue(f.queries.toString(), f.queries.contains("SELECT \"qr\" FROM \"ERP_BASE\".\"TB_BASE_E_INVOICE\" WHERE \"UPDATED_AT\" IS NOT NULL"));
            assertEquals(128, f.fetchSize);
        }
    }

    @Test
    public void cancellationStopsPreflightAndDriverErrorsDoNotLeakRawValues() throws Exception {
        Fixture f = new Fixture(Types.BLOB, "STRING");
        StarRocksBinaryTextTransfer transfer = f.prepare();
        DataSyncCancellation cancellation = new DataSyncCancellation();
        try {
            cancellation.requestStop();
            try {
                transfer.preflight(f.source(), "erp_base", "tb_base_e_invoice", null, cancellation);
                fail("Expected cancellation");
            } catch (DataSyncCancellation.SyncStoppedException expected) { }
        } finally {
            cancellation.close();
        }
        f.readFailure = new SQLException("secret QR payload password=hidden", "08001", 7);
        try {
            transfer.preflight(f.source(), "erp_base", "tb_base_e_invoice", null, null);
            fail("Expected sanitized read failure");
        } catch (SQLException expected) {
            assertFalse(expected.getMessage().contains("secret"));
            assertFalse(expected.getMessage().contains("password"));
            assertNull(expected.getCause());
            assertEquals("08001", expected.getSQLState());
            assertEquals(7, expected.getErrorCode());
        }
    }

    private static void rejectRead(StarRocksBinaryTextTransfer transfer, ResultSet row, String message, String state) throws Exception {
        try { transfer.read(row, "qr"); fail("Expected read failure"); }
        catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
            assertEquals(state, expected.getSQLState());
        }
    }

    private static void assertSanitized(Throwable failure) {
        assertFalse(failure.toString(), failure.toString().contains("secret"));
        assertFalse(failure.toString(), failure.toString().contains("password"));
        assertNull(failure.getCause());
        for (Throwable suppressed : failure.getSuppressed()) assertSanitized(suppressed);
    }

    private static final class Fixture {
        final int jdbcType;
        final String targetType;
        String sourceType = "MYSQL";
        final List<String> queries = new ArrayList<>();
        List<Object> values = Collections.emptyList();
        int targetReads;
        int fetchSize;
        boolean preflightClosed;
        boolean missingScalarTarget;
        SQLException readFailure;
        SQLException metadataFailure;
        SQLException targetMetadataFailure;

        Fixture(int jdbcType, String targetType) { this.jdbcType = jdbcType; this.targetType = targetType; }

        StarRocksBinaryTextTransfer prepare() throws SQLException {
            return StarRocksBinaryTextTransfer.prepare(sourceType, source(), target(), "erp_base", "tb_base_e_invoice",
                    "`erp_base`.`tb_base_e_invoice`", Arrays.asList("qr", "id"), Arrays.asList("qr", "id"), null);
        }

        Connection source() {
            return proxy(Connection.class, (name, args) -> {
                if ("createStatement".equals(name)) return proxy(Statement.class, (method, parameters) -> {
                    if ("close".equals(method)) return null;
                    if ("executeQuery".equals(method)) {
                        queries.add((String) parameters[0]);
                        return proxy(ResultSet.class, (operation, arguments) -> {
                            if ("close".equals(operation)) return null;
                            if ("getMetaData".equals(operation)) return proxy(ResultSetMetaData.class, (metadata, index) -> {
                                if (metadataFailure != null) throw metadataFailure;
                                if ("getColumnCount".equals(metadata)) return 2;
                                if ("getColumnType".equals(metadata)) return (Integer) index[0] == 1 ? jdbcType : Types.INTEGER;
                                throw new AssertionError(metadata);
                            });
                            throw new AssertionError(operation);
                        });
                    }
                    throw new AssertionError(method);
                });
                if ("prepareStatement".equals(name)) {
                    queries.add((String) args[0]);
                    return proxy(PreparedStatement.class, (method, parameters) -> {
                        if ("close".equals(method)) { preflightClosed = true; return null; }
                        if ("setFetchSize".equals(method)) { fetchSize = (Integer) parameters[0]; return null; }
                        if ("executeQuery".equals(method)) {
                            if (readFailure != null) throw readFailure;
                            int[] index = {-1};
                            return proxy(ResultSet.class, (operation, arguments) -> {
                                if ("close".equals(operation)) return null;
                                if ("next".equals(operation)) return ++index[0] < values.size();
                                if ("getObject".equals(operation)) return values.get(index[0]);
                                throw new AssertionError(operation);
                            });
                        }
                        throw new AssertionError(method);
                    });
                }
                throw new AssertionError(name);
            });
        }

        Connection target() {
            return proxy(Connection.class, (name, args) -> {
                if ("createStatement".equals(name)) return proxy(Statement.class, (method, parameters) -> {
                    if ("close".equals(method)) return null;
                    if ("executeQuery".equals(method)) {
                        targetReads++;
                        queries.add((String) parameters[0]);
                        if (targetMetadataFailure != null) throw targetMetadataFailure;
                        int[] index = {-1};
                        return proxy(ResultSet.class, (operation, arguments) -> {
                            if ("close".equals(operation)) return null;
                            if ("next".equals(operation)) return ++index[0] < (missingScalarTarget ? 1 : 2);
                            if ("getString".equals(operation)) return "Field".equals(arguments[0])
                                    ? index[0] == 0 ? "qr" : "id" : index[0] == 0 ? targetType : "INT";
                            throw new AssertionError(operation);
                        });
                    }
                    throw new AssertionError(method);
                });
                throw new AssertionError(name);
            });
        }

        ResultSet row(Object value) {
            return proxy(ResultSet.class, (name, args) -> {
                if ("getObject".equals(name)) return value;
                if ("getBinaryStream".equals(name)) return new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8));
                throw new AssertionError(name);
            });
        }
    }

    private static final class BlobValue {
        final byte[] bytes;
        final long length;
        boolean freed;
        boolean closed;
        int readBytes;
        SQLException lengthFailure;
        SQLException streamFailure;
        SQLException freeFailure;
        BlobValue(byte[] bytes, long length) { this.bytes = bytes; this.length = length; }
        Blob proxy() {
            return StarRocksBinaryTextTransferTest.proxy(Blob.class, (name, args) -> {
                if ("length".equals(name)) { if (lengthFailure != null) throw lengthFailure; return length; }
                if ("free".equals(name)) { freed = true; if (freeFailure != null) throw freeFailure; return null; }
                if ("getBinaryStream".equals(name)) {
                    if (streamFailure != null) throw streamFailure;
                    return new InputStream() {
                    int index;
                    @Override public int read() { if (index >= bytes.length) return -1; readBytes++; return bytes[index++] & 0xff; }
                    @Override public void close() { closed = true; }
                };
                }
                throw new AssertionError(name);
            });
        }
    }

    private interface Invocation { Object invoke(String name, Object[] args) throws Throwable; }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(method.getName())) return "binary text fixture";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(object);
                if ("equals".equals(method.getName())) return object == args[0];
            }
            return invocation.invoke(method.getName(), args);
        }));
    }
}
