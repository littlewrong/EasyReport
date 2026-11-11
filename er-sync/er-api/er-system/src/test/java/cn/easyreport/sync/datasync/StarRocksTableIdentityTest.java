package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDatasource;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Identity checks must complete before target schema, data, or checkpoints can change. */
public class StarRocksTableIdentityTest {
    @Test
    public void sameEndpointRejectsWithoutRequiringFrontendPrivileges() throws Exception {
        Fixture f = new Fixture();
        f.targetUrl = f.datasource.getJdbcUrl();
        f.expectSameTable();
        assertTrue(f.queries.isEmpty());
    }

    @Test
    public void sameClusterThroughDifferentFrontendOrDnsAliasRejects() throws Exception {
        Fixture f = new Fixture();
        f.targetIds = f.sourceIds.clone();
        f.expectSameTable();
        assertEquals(Arrays.asList("source:SHOW FRONTENDS", "target:SHOW FRONTENDS"), f.queries);
    }

    @Test
    public void catalogEndpointAliasKeepsExistingIdentityProtection() throws Exception {
        Fixture f = new Fixture();
        try {
            StarRocksTableIdentity.verifyDistinct(f.datasource, f.source(), f.target(), "warehouse", "orders",
                    "`warehouse`.`orders`", f.targetUrl, null);
            fail("Catalog endpoint names the destination cluster");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("源表与目标表为同一表"));
        }
        assertTrue(f.queries.isEmpty());
    }

    @Test
    public void separateClustersAllowSameDatabaseAndTableName() throws Exception {
        Fixture f = new Fixture();
        f.verify("warehouse", "orders", "`warehouse`.`orders`");
        assertEquals(Arrays.asList("source:SHOW FRONTENDS", "target:SHOW FRONTENDS"), f.queries);
    }

    @Test
    public void differentTableOrDatabaseNeedsNoClusterPrivileges() throws Exception {
        Fixture f = new Fixture();
        Connection unused = proxy(Connection.class, (method, args) -> {
            throw new AssertionError("Distinct names require no metadata lookup: " + method.getName());
        });
        for (String destination : Arrays.asList("`warehouse`.`other_orders`", "`other_warehouse`.`orders`")) {
            StarRocksTableIdentity.verifyDistinct(f.datasource, unused, unused, "warehouse", "orders",
                    destination, null, null);
        }
    }

    @Test
    public void unqualifiedTargetUsesItsActualCurrentDatabase() throws Exception {
        Fixture f = new Fixture();
        f.targetUrl = f.datasource.getJdbcUrl();
        try {
            f.verify("warehouse", "orders", "orders");
            fail("Unqualified target still points at its source table");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("源表与目标表为同一表"));
        }
        f.targetCatalog = "other_warehouse";
        f.verify("warehouse", "orders", "`orders`");
        assertTrue(f.queries.isEmpty());
    }

    @Test
    public void escapedIdentifiersAndDefaultMysqlPortCannotBypassIdentityCheck() throws Exception {
        Fixture f = new Fixture();
        f.datasource.setJdbcUrl("jdbc:mysql://same-host/warehouse");
        f.targetUrl = "jdbc:mysql://same-host:3306/warehouse";
        try {
            f.verify("ware`house", "or`ders", "`ware``house`.`or``ders`");
            fail("Escaped names and implicit Connector/J port identify the same table");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("源表与目标表为同一表"));
        }
        assertTrue(f.queries.isEmpty());
    }

    @Test
    public void missingOrInconsistentClusterIdentityFailsClosed() throws Exception {
        for (Long[] ids : new Long[][] { {}, {null}, {0L}, {-1L}, {11L, 12L} }) {
            Fixture f = new Fixture();
            f.sourceIds = ids;
            try {
                f.verify("warehouse", "orders", "`warehouse`.`orders`");
                fail("Unverified cluster identity must preserve the destination");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("集群身份"));
                assertTrue(expected.getMessage().contains("尚未清空目标表"));
            }
            assertEquals(Collections.singletonList("source:SHOW FRONTENDS"), f.queries);
        }
    }

    @Test
    public void frontendPermissionFailureDoesNotLeakDriverConnectionDetails() throws Exception {
        Fixture f = new Fixture();
        f.sourceProbeFailure = new SQLException("secret-in-driver", "42000", 1044);
        try {
            f.verify("warehouse", "orders", "`warehouse`.`orders`");
            fail("No privilege means identity cannot be established");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("SHOW FRONTENDS"));
            assertTrue(expected.getMessage().contains("尚未清空目标表"));
            assertFalse(expected.getMessage().contains("secret-in-driver"));
            assertNull(expected.getCause());
            assertEquals("42000", expected.getSQLState());
        }
    }

    @Test
    public void unknownCurrentDatabaseFailsClosedForUnqualifiedTarget() throws Exception {
        Fixture f = new Fixture();
        f.targetCatalog = null;
        try {
            f.verify("warehouse", "orders", "orders");
            fail("An unknown destination database must not be assumed different");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("数据库"));
        }
        assertTrue(f.queries.isEmpty());
    }

    @Test
    public void initializationChecksIdentityBeforeReadingColumnsOrChangingTargetSchema() throws Exception {
        for (String importMethod : Arrays.asList(null, ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE,
                ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG)) {
            Fixture f = new Fixture();
            f.targetIds = f.sourceIds.clone();
            ErDataTransfer task = new ErDataTransfer();
            task.setTransferId(42L);
            task.setSyncMode("0");
            task.setExternalTableEnabled("1");
            task.setDirectImportMethod(importMethod);
            task.setTargetTable("warehouse.orders");
            StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(null, null, null) {
                @Override
                protected void prepareTargetSchema(Connection source, Connection target, String schema,
                        String table, String destination) {
                    throw new AssertionError("Identity rejection must precede CHAR/VARCHAR schema changes");
                }
            };
            DataSyncExtractor unusedExtractor = proxy(DataSyncExtractor.class, (method, args) -> {
                throw new AssertionError("Identity rejection must precede source column lookup");
            });
            DataSyncLoader unusedLoader = proxy(DataSyncLoader.class, (method, args) -> {
                throw new AssertionError("Identity rejection must precede target loader preparation");
            });
            Method sync = DataSyncExecutor.class.getDeclaredMethod("syncTable", ErDataTransfer.class,
                    ErDatasource.class, String.class, String.class, DataSyncExtractor.class,
                    DataSyncLoader.class, Connection.class, Connection.class, int.class,
                    cn.easyreport.sync.core.SyncProgressCallback.class, Map.class);
            sync.setAccessible(true);
            try {
                sync.invoke(executor, task, f.datasource, "warehouse", "orders", unusedExtractor,
                        unusedLoader, f.source(), f.target(), 1000, null, Collections.emptyMap());
                fail("Initialization of the source itself must be rejected");
            } catch (InvocationTargetException failure) {
                assertTrue(failure.getCause().toString(), failure.getCause() instanceof IllegalArgumentException);
                assertTrue(failure.getCause().getMessage().contains("源表与目标表为同一表"));
            }
            assertEquals(Arrays.asList("source:SHOW FRONTENDS", "target:SHOW FRONTENDS"), f.queries);
        }
    }

    @Test
    public void incrementalAndNonStarRocksSourceDoNotRunInitializationIdentityChecks() throws Exception {
        Fixture f = new Fixture();
        Connection unused = proxy(Connection.class, (method, args) -> {
            throw new AssertionError("Unrelated task must not acquire extra metadata privileges");
        });
        StarRocksDataSyncExecutor executor = new StarRocksDataSyncExecutor(null, null, null);
        ErDataTransfer task = new ErDataTransfer();
        task.setSyncMode("1");
        executor.validateSourceTargetIdentity(task, f.datasource, unused, unused, "warehouse", "orders", "`warehouse`.`orders`");
        task.setSyncMode("0");
        f.datasource.setDatasourceType("MYSQL");
        executor.validateSourceTargetIdentity(task, f.datasource, unused, unused, "warehouse", "orders", "`warehouse`.`orders`");
    }

    private static final class Fixture {
        final ErDatasource datasource = new ErDatasource();
        final List<String> queries = new ArrayList<>();
        String targetUrl = "jdbc:mysql://target-fe:9030/warehouse";
        String targetCatalog = "warehouse";
        Long[] sourceIds = {11L, 11L};
        Long[] targetIds = {22L, 22L};
        SQLException sourceProbeFailure;

        Fixture() {
            datasource.setDatasourceType("STARROCKS");
            datasource.setDatabaseName("warehouse");
            datasource.setJdbcUrl("jdbc:mysql://source-fe:9030/warehouse");
        }

        void verify(String schema, String table, String targetTable) throws Exception {
            StarRocksTableIdentity.verifyDistinct(datasource, source(), target(), schema, table, targetTable, null, null);
        }

        void expectSameTable() throws Exception {
            try {
                verify("warehouse", "orders", "`warehouse`.`orders`");
                fail("Source and destination identify the same table");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("源表与目标表为同一表"));
            }
        }

        Connection source() { return connection(true); }
        Connection target() { return connection(false); }

        Connection connection(boolean source) {
            return proxy(Connection.class, (method, args) -> {
                if ("getCatalog".equals(method.getName())) return source ? "warehouse" : targetCatalog;
                if ("getMetaData".equals(method.getName())) return proxy(DatabaseMetaData.class, (call, values) -> {
                    if ("getURL".equals(call.getName())) return source ? datasource.getJdbcUrl() : targetUrl;
                    throw new AssertionError(call.getName());
                });
                if ("createStatement".equals(method.getName())) return proxy(Statement.class, (call, values) -> {
                    if ("close".equals(call.getName())) return null;
                    assertEquals("Identity checks may only read cluster metadata", "executeQuery", call.getName());
                    assertEquals("SHOW FRONTENDS", values[0]);
                    queries.add((source ? "source:" : "target:") + values[0]);
                    if (source && sourceProbeFailure != null) throw sourceProbeFailure;
                    return rows(source ? sourceIds : targetIds);
                });
                throw new AssertionError(method.getName());
            });
        }
    }

    private static ResultSet rows(Long[] ids) {
        int[] index = {-1};
        return proxy(ResultSet.class, (method, args) -> {
            if ("next".equals(method.getName())) return ++index[0] < ids.length;
            if ("close".equals(method.getName())) return null;
            if ("wasNull".equals(method.getName())) return ids[index[0]] == null;
            if ("getLong".equals(method.getName())) {
                assertEquals("ClusterId", args[0]);
                return ids[index[0]] == null ? 0L : ids[index[0]];
            }
            throw new AssertionError(method.getName());
        });
    }

    private interface Invocation {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (object, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return type.getSimpleName() + " identity fixture";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(object);
                        if ("equals".equals(method.getName())) return object == args[0];
                    }
                    return invocation.invoke(method, args);
                }));
    }
}
