package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.strategy.DatabaseStrategyFactory;
import com.kingbase8.util.KBobject;
import dm.jdbc.driver.DmdbTimestamp;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class DmDataSyncTest {
    private final DmDataSyncLoader loader = new DmDataSyncLoader();

    @Test
    public void mergeUsesEveryKeyAndDoesNotUpdateOnClauseColumns() {
        assertEquals("MERGE INTO \"App\".\"Orders\" t USING (SELECT ? AS \"Tenant\", ? AS \"Id\", ? AS \"Name\" FROM DUAL) s"
                + " ON (t.\"Tenant\" = s.\"Tenant\" AND t.\"Id\" = s.\"Id\")"
                + " WHEN MATCHED THEN UPDATE SET t.\"Name\" = s.\"Name\""
                + " WHEN NOT MATCHED THEN INSERT (\"Tenant\",\"Id\",\"Name\") VALUES (s.\"Tenant\",s.\"Id\",s.\"Name\")",
            loader.buildUpsertSql("App.Orders", Arrays.asList("Tenant", "Id", "Name"), true, Arrays.asList("Tenant", "Id")));
        String onlyKey = loader.buildUpsertSql("Key_Table", Collections.singletonList("Id"), true,
            Collections.singletonList("Id"));
        assertFalse(onlyKey.contains("WHEN MATCHED"));
        assertTrue(onlyKey.endsWith("WHEN NOT MATCHED THEN INSERT (\"Id\") VALUES (s.\"Id\")"));
    }

    @Test
    public void nonKeyIdentityIsInsertedButNotUpdatedAndPreparationResetsForEachTable() throws Exception {
        IdentityJdbc jdbc = new IdentityJdbc(true);
        loader.prepareTarget(jdbc.connection, "App.WithIdentity");
        String sql = loader.buildUpsertSql("App.WithIdentity", Arrays.asList("Name", "Id", "Payload"), true,
            Collections.singletonList("Name"));
        assertFalse(sql.contains("t.\"Id\" = s.\"Id\""));
        assertTrue(sql.contains("UPDATE SET t.\"Payload\" = s.\"Payload\""));
        assertTrue(sql.endsWith("INSERT (\"Name\",\"Id\",\"Payload\") VALUES (s.\"Name\",s.\"Id\",s.\"Payload\")"));

        jdbc.identity = false;
        loader.prepareTarget(jdbc.connection, "App.Plain");
        String next = loader.buildUpsertSql("App.Plain", Arrays.asList("Name", "Id"), true,
            Collections.singletonList("Name"));
        assertTrue(next.contains("WHEN MATCHED THEN UPDATE SET t.\"Id\" = s.\"Id\""));
        assertEquals(Arrays.asList("SELECT * FROM \"App\".\"WithIdentity\" WHERE 1=0",
            "SELECT * FROM \"App\".\"Plain\" WHERE 1=0"), jdbc.sql);
    }

    @Test
    public void existingLoadersDoNotQueryOrChangeTheTargetDuringPreparation() throws Exception {
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                throw new AssertionError("Default preparation must not access the connection: " + method.getName());
            });
        for (DataSyncLoader other : Arrays.asList(new MySqlDataSyncLoader(), new TiDbDataSyncLoader(),
                new PostgreSqlDataSyncLoader(), new OracleDataSyncLoader(), new SqlServerDataSyncLoader(),
                new StarRocksDataSyncLoader(), new KingbaseDataSyncLoader())) {
            other.prepareTarget(connection, "App.Orders");
        }
    }

    @Test
    public void insertDeleteAndQualifiedIdentifiersAreQuotedWithoutChangingCase() {
        String name = "\"App.With.Dot\".\"Order\"\"Items\"";
        assertEquals("INSERT INTO " + name + "(\"Id\",\"Na\"\"me\") VALUES (?,?)",
            loader.buildUpsertSql(name, Arrays.asList("Id", "Na\"me"), false));
        assertEquals("DELETE FROM " + name + " WHERE \"Id\" IN ('O''Brien','2')",
            loader.buildDeleteSql(name, "Id", Arrays.asList("O'Brien", "2")));
        assertEquals("DELETE FROM \"App\".\"Orders\" WHERE 1=0",
            loader.buildDeleteSql("App.Orders", "Id", Collections.emptyList()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void mergeRequiresPresentKeyColumns() {
        loader.buildUpsertSql("Orders", Collections.singletonList("Name"), true, Collections.singletonList("Id"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidQualifiedNamesAreRejected() {
        loader.buildUpsertSql("\"Unfinished", Collections.singletonList("Id"), false);
    }

    @Test
    public void factoriesSelectIndependentDmComponentsAndKeepKingbaseRoute() throws Exception {
        ErDatasource ds = new ErDatasource();
        ds.setDatasourceType("dm");
        assertTrue(DataSyncFactory.buildExtractor(ds) instanceof DmDataSyncExtractor);
        assertTrue(DataSyncFactory.buildLoader(ds) instanceof DmDataSyncLoader);
        DataSyncExecutor executor = DataSyncExecutorFactory.create(ds, null, null, null);
        try { assertTrue(executor instanceof DmDataSyncExecutor); }
        finally { executor.cancellationContext().close(); }
        ds.setDatasourceType("KINGBASE");
        assertTrue(DataSyncFactory.buildExtractor(ds) instanceof KingbaseDataSyncExtractor);
        assertTrue(DataSyncFactory.buildLoader(ds) instanceof KingbaseDataSyncLoader);
    }

    @Test
    public void identitySwitchIsConditionalAndRestoredWithoutCommitting() throws Exception {
        IdentityJdbc jdbc = new IdentityJdbc(true);
        DmDataSyncExecutor executor = new DmDataSyncExecutor(null, null, null);
        try {
            executor.beforeBatchInsert(jdbc.connection, "\"App\".\"Order\"\"Items\"");
            executor.afterBatchInsert(jdbc.connection, "\"App\".\"Order\"\"Items\"");
            assertEquals(Arrays.asList("SELECT * FROM \"App\".\"Order\"\"Items\" WHERE 1=0",
                "SET IDENTITY_INSERT \"App\".\"Order\"\"Items\" ON",
                "SET IDENTITY_INSERT \"App\".\"Order\"\"Items\" OFF"), jdbc.sql);
            jdbc.sql.clear();
            jdbc.identity = false;
            executor.beforeBatchInsert(jdbc.connection, "App.Plain");
            executor.afterBatchInsert(jdbc.connection, "App.Plain");
            assertEquals(Collections.singletonList("SELECT * FROM \"App\".\"Plain\" WHERE 1=0"), jdbc.sql);
        } finally { executor.cancellationContext().close(); }
    }

    @Test
    public void cleanupFailureIsReportedAndDoesNotLeaveExecutorStateEnabled() throws Exception {
        IdentityJdbc jdbc = new IdentityJdbc(true);
        DmDataSyncExecutor executor = new DmDataSyncExecutor(null, null, null);
        try {
            executor.beforeBatchInsert(jdbc.connection, "App.Orders");
            jdbc.failOff = true;
            try { executor.afterBatchInsert(jdbc.connection, "App.Orders"); fail("cleanup failure must be visible"); }
            catch (SQLException expected) { assertEquals("identity reset failed", expected.getMessage()); }
            int calls = jdbc.sql.size();
            executor.afterBatchInsert(jdbc.connection, "App.Orders");
            assertEquals(calls, jdbc.sql.size());
        } finally { executor.cancellationContext().close(); }
    }

    @Test
    public void bufferedFullInsertSplitsAtDmParameterLimitAndKeepsTransactionOwnership() throws Exception {
        List<String> queries = new ArrayList<>();
        List<Integer> parameterCounts = new ArrayList<>();
        Connection conn = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (!"prepareStatement".equals(method.getName())) throw new AssertionError("Unexpected connection call: " + method.getName());
                queries.add((String) args[0]);
                int[] parameters = {0};
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (statement, operation, values) -> {
                        switch (operation.getName()) {
                            case "setObject": assertEquals(++parameters[0], values[0]); return null;
                            case "executeUpdate": parameterCounts.add(parameters[0]); return 1;
                            case "close": return null;
                            default: throw new AssertionError("Unexpected statement call: " + operation.getName());
                        }
                    });
            });
        DmDataSyncExecutor executor = new DmDataSyncExecutor(null, null, null);
        try {
            Field strategy = DataSyncExecutor.class.getDeclaredField("targetStrategy");
            strategy.setAccessible(true);
            strategy.set(executor, DatabaseStrategyFactory.getStrategy("DM"));
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 1000; i++) rows.add(new Object[]{i, "name", null});
            executor.executeBufferedFullInsert(conn, rows, "App.Orders", Arrays.asList("Id", "Name", "Empty"), Collections.singletonList("Id"));
            assertEquals(Arrays.asList(1998, 1002), parameterCounts);
            assertEquals(2, queries.size());
            assertTrue(queries.get(0).startsWith("INSERT INTO \"App\".\"Orders\" (\"Id\",\"Name\",\"Empty\") VALUES (?,?,?),(?,?,?)"));
            assertEquals("SELECT * FROM (SELECT \"Id\", \"Changed\" FROM \"App\".\"Orders\" WHERE \"Changed\" >= ?"
                + " ORDER BY \"Changed\", \"Id\") WHERE ROWNUM <= ?",
                executor.buildAlignDeleteSelectSql("\"App\".\"Orders\"", "Id", "Changed"));
            assertEquals(1000, executor.getDeleteBatchSize());
        } finally { executor.cancellationContext().close(); }
    }

    @Test
    public void nativeDriverTimestampsBecomeStandardJdbcValuesAcrossTargets() {
        DmdbTimestamp nativeTime = DmdbTimestamp.valueOf("2026-06-01 12:34:56.123456");
        Timestamp actual = (Timestamp) new DefaultDataSyncValueConverter().convertValue(nativeTime);
        assertEquals(Timestamp.class, actual.getClass());
        assertEquals(nativeTime.toTimestamp(), actual);
        Timestamp future = Timestamp.valueOf("2042-01-01 12:34:56.123456");
        DmdbTimestamp nativeFuture = DmdbTimestamp.valueOf(future);
        assertEquals(Timestamp.class, new DmDataSyncValueConverter().convertValue(nativeFuture).getClass());
        assertEquals(future, new OracleDataSyncValueConverter().convertValue(nativeFuture));
    }

    @Test
    public void dmTargetKeepsUnsignedBitsTemporalPrecisionLobsAndForeignObjectValues() throws Exception {
        DmDataSyncValueConverter converter = new DmDataSyncValueConverter();
        assertEquals(new BigDecimal("18446744073709551615"), converter.convertColumnValue(
            new byte[]{-1, -1, -1, -1, -1, -1, -1, -1}, "BIT"));
        assertEquals(Integer.valueOf(1), converter.convertColumnValue(Boolean.TRUE, "BIT"));
        assertEquals(Boolean.TRUE, converter.convertColumnValue(Boolean.TRUE, "BOOLEAN"));
        assertEquals("10101", converter.convertColumnValue("10101", "VARBIT"));
        Timestamp future = Timestamp.valueOf("2042-01-01 12:34:56.123456");
        assertSame(future, converter.convertValue(future));
        assertEquals("hello", converter.convertValue(new javax.sql.rowset.serial.SerialClob("hello".toCharArray())));
        assertArrayEquals(new byte[]{1, 2}, (byte[]) converter.convertValue(new javax.sql.rowset.serial.SerialBlob(new byte[]{1, 2})));
        KBobject kb = new KBobject(); kb.setType("jsonb"); kb.setValue("{\"ok\":true}");
        assertEquals(kb.getValue(), converter.convertValue(kb));
        org.postgresql.util.PGInterval interval = new org.postgresql.util.PGInterval(1, 2, 3, 4, 5, 6);
        assertEquals(interval.getValue(), converter.convertValue(interval));
    }

    private static final class IdentityJdbc {
        final List<String> sql = new ArrayList<>();
        final Connection connection;
        boolean identity;
        boolean failOff;

        IdentityJdbc(boolean identity) {
            this.identity = identity;
            ClassLoader classes = getClass().getClassLoader();
            ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(classes, new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> {
                    if ("getColumnCount".equals(method.getName())) return 2;
                    if ("isAutoIncrement".equals(method.getName())) return this.identity && ((Integer) args[0]) == 1;
                    if ("getColumnName".equals(method.getName())) return ((Integer) args[0]) == 1 ? "Id" : "Name";
                    throw new AssertionError("Unexpected metadata call: " + method.getName());
                });
            ResultSet result = (ResultSet) Proxy.newProxyInstance(classes, new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) return metadata;
                    if ("close".equals(method.getName())) return null;
                    throw new AssertionError("Empty metadata query must not fetch rows: " + method.getName());
                });
            Statement statement = (Statement) Proxy.newProxyInstance(classes, new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) { sql.add((String) args[0]); return result; }
                    if ("execute".equals(method.getName())) {
                        String query = (String) args[0]; sql.add(query);
                        if (failOff && query.endsWith(" OFF")) throw new SQLException("identity reset failed");
                        return false;
                    }
                    if ("close".equals(method.getName())) return null;
                    throw new AssertionError("Unexpected statement call: " + method.getName());
                });
            connection = (Connection) Proxy.newProxyInstance(classes, new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) return statement;
                    throw new AssertionError("Identity setup must not commit or close connection: " + method.getName());
                });
        }
    }
}
