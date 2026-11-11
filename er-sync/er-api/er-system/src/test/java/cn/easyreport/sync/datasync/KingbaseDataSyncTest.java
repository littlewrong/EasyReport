package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.strategy.DatabaseStrategyFactory;
import com.kingbase8.util.KBobject;
import org.postgresql.util.PGobject;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class KingbaseDataSyncTest {
    private final KingbaseDataSyncLoader loader = new KingbaseDataSyncLoader();

    @Test
    public void upsertUsesCompositeKeyAndRetainsIdentityValuesWithoutUpdatingKeys() {
        assertEquals("INSERT INTO \"App\".\"Orders\"(\"Tenant\",\"Id\",\"Name\") OVERRIDING SYSTEM VALUE VALUES (?,?,?)"
            + " ON CONFLICT (\"Tenant\",\"Id\") DO UPDATE SET \"Name\" = EXCLUDED.\"Name\"",
            loader.buildUpsertSql("App.Orders", Arrays.asList("Tenant", "Id", "Name"), true, Arrays.asList("Tenant", "Id")));
        assertEquals("INSERT INTO \"OnlyKey\"(\"Id\") OVERRIDING SYSTEM VALUE VALUES (?) ON CONFLICT (\"Id\") DO NOTHING",
            loader.buildUpsertSql("OnlyKey", Collections.singletonList("Id"), true, Collections.singletonList("Id")));
    }

    @Test
    public void plainInsertAndDeletePreserveQuotedNamesAndEscapeValues() {
        String table = "\"App.With.Dot\".\"Order\"\"Items\"";
        assertEquals("INSERT INTO " + table + "(\"Id\",\"Na\"\"me\") OVERRIDING SYSTEM VALUE VALUES (?,?)",
            loader.buildUpsertSql(table, Arrays.asList("Id", "Na\"me"), false));
        assertEquals("DELETE FROM " + table + " WHERE \"Id\" IN ('O''Brien','2')",
            loader.buildDeleteSql(table, "Id", Arrays.asList("O'Brien", "2")));
        assertEquals("DELETE FROM \"App\".\"Orders\" WHERE 1=0",
            loader.buildDeleteSql("App.Orders", "Id", Collections.emptyList()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void upsertRejectsMissingPrimaryKeyInsteadOfProducingInvalidSql() {
        loader.buildUpsertSql("Orders", Collections.singletonList("Name"), true, Collections.singletonList("Id"));
    }

    @Test
    public void factoriesSelectKingbaseComponentsWithoutChangingExistingRoutes() throws Exception {
        ErDatasource ds = new ErDatasource();
        ds.setDatasourceType("kingbase");
        assertTrue(DataSyncFactory.buildExtractor(ds) instanceof KingbaseDataSyncExtractor);
        assertTrue(DataSyncFactory.buildLoader(ds) instanceof KingbaseDataSyncLoader);
        DataSyncExecutor executor = DataSyncExecutorFactory.create(ds, null, null, null);
        try {
            assertTrue(executor instanceof KingbaseDataSyncExecutor);
        } finally {
            executor.cancellationContext().close();
        }
        ds.setDatasourceType("POSTGRESQL");
        assertTrue(DataSyncFactory.buildExtractor(ds) instanceof PostgreSqlDataSyncExtractor);
        assertTrue(DataSyncFactory.buildLoader(ds) instanceof PostgreSqlDataSyncLoader);
        executor = DataSyncExecutorFactory.create(ds, null, null, null);
        try {
            assertTrue(executor instanceof PostgreSqlDataSyncExecutor);
        } finally {
            executor.cancellationContext().close();
        }
    }

    @Test
    public void realKingbaseDriverObjectsArePreservedOnlyForKingbaseTargets() throws Exception {
        KBobject object = new KBobject();
        object.setType("jsonb");
        object.setValue("{\"ok\":true}");
        assertSame(object, new KingbaseDataSyncValueConverter().convertValue(object));
        assertEquals("{\"ok\":true}", new DefaultDataSyncValueConverter().convertValue(object));
        PGobject pgObject = (PGobject) new DefaultDataSyncValueConverter(true).convertValue(object);
        assertEquals("jsonb", pgObject.getType());
        assertEquals("{\"ok\":true}", pgObject.getValue());
        assertEquals("{\"ok\":true}", new OracleDataSyncValueConverter().convertValue(object));
        KBobject nullObject = new KBobject();
        nullObject.setType("jsonb");
        assertNull(new DefaultDataSyncValueConverter().convertValue(nullObject));
        assertNull(new DefaultDataSyncValueConverter(true).convertValue(nullObject));
    }

    @Test
    public void typedJsonCrossesDriverBoundaryAndOtherObjectsUseTheirMappedTextRepresentation() throws Exception {
        for (String type : Arrays.asList("json", "jsonb")) {
            KBobject source = new KBobject();
            source.setType(type);
            source.setValue("{\"text\":\"中文\"}");
            PGobject pg = (PGobject) new DefaultDataSyncValueConverter(true).convertValue(source);
            assertEquals(type, pg.getType());
            assertEquals(source.getValue(), pg.getValue());
            assertSame(pg, new DefaultDataSyncValueConverter(true).convertValue(pg));
            assertEquals(source.getValue(), new KingbaseDataSyncValueConverter().convertValue(pg));
        }
        KBobject custom = new KBobject();
        custom.setType("custom_domain");
        custom.setValue("domain value");
        assertEquals("domain value", new DefaultDataSyncValueConverter(true).convertValue(custom));
        assertEquals("domain value", new DefaultDataSyncValueConverter().convertValue(custom));
        assertEquals("domain value", new OracleDataSyncValueConverter().convertValue(custom));
        assertSame(custom, new KingbaseDataSyncValueConverter().convertValue(custom));
        org.postgresql.util.PGInterval interval = new org.postgresql.util.PGInterval(1, 2, 3, 4, 5, 6);
        assertEquals(interval.getValue(), new KingbaseDataSyncValueConverter().convertValue(interval));
    }

    @Test
    public void kingbaseBitConversionKeepsUnsigned64BitValuesExact() {
        KingbaseDataSyncValueConverter converter = new KingbaseDataSyncValueConverter();
        assertEquals(new BigDecimal("18446744073709551615"), converter.convertColumnValue(
            new byte[]{-1, -1, -1, -1, -1, -1, -1, -1}, "BIT"));
        assertEquals(BigDecimal.ZERO, converter.convertColumnValue(new byte[0], "BIT"));
        assertEquals(Boolean.TRUE, converter.convertColumnValue(Boolean.TRUE, "BIT"));
    }

    @Test
    public void kingbaseKeepsWideYearTimestampsAndNormalizesLobsThroughSharedConverter() throws Exception {
        Timestamp future = Timestamp.valueOf("2042-01-01 12:34:56.123456");
        KingbaseDataSyncValueConverter converter = new KingbaseDataSyncValueConverter();
        assertSame(future, converter.convertValue(future));
        assertEquals(future, converter.convertValue(future.toLocalDateTime()));
        assertEquals("hello", converter.convertValue(new javax.sql.rowset.serial.SerialClob("hello".toCharArray())));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) converter.convertValue(
            new javax.sql.rowset.serial.SerialBlob(new byte[]{1, 2, 3})));
    }

    @Test
    public void fullInsertBindsEveryValueAndUsesIdentityOverrideWithoutChangingTransactionOwnership() throws Exception {
        List<Object> bound = new ArrayList<>();
        String[] sql = {null};
        boolean[] closed = {false};
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setObject": assertEquals(bound.size() + 1, args[0]); bound.add(args[1]); return null;
                    case "executeUpdate": return 2;
                    case "close": closed[0] = true; return null;
                    default: throw new AssertionError("Unexpected statement call: " + method.getName());
                }
            });
        Connection conn = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) { sql[0] = (String) args[0]; return statement; }
                throw new AssertionError("Insert must not commit or close the connection: " + method.getName());
            });
        KingbaseDataSyncExecutor executor = new KingbaseDataSyncExecutor(null, null, null);
        try {
            Field strategy = DataSyncExecutor.class.getDeclaredField("targetStrategy");
            strategy.setAccessible(true);
            strategy.set(executor, DatabaseStrategyFactory.getStrategy("KINGBASE"));
            executor.executeFullInsertStatement(conn, Arrays.asList(new Object[]{1, "a"}, new Object[]{2, "b"}),
                0, 2, "\"App\".\"Orders\"", Arrays.asList("Id", "Name"));
            assertEquals("INSERT INTO \"App\".\"Orders\" (\"Id\",\"Name\") OVERRIDING SYSTEM VALUE VALUES (?,?),(?,?)", sql[0]);
            assertEquals(Arrays.asList(1, "a", 2, "b"), bound);
            assertTrue(closed[0]);
            assertEquals("\"App\".\"Order\"\"Items\"", executor.qualify("App", "Order\"Items"));
            assertEquals("\"Orders\"", executor.qualify(null, "Orders"));
            assertEquals("SELECT \"Id\", \"Changed\" FROM \"App\".\"Orders\" WHERE \"Changed\" >= ?"
                + " ORDER BY \"Changed\", \"Id\" FETCH FIRST ? ROWS ONLY",
                executor.buildAlignDeleteSelectSql("\"App\".\"Orders\"", "Id", "Changed"));
        } finally {
            executor.cancellationContext().close();
        }
    }
}
