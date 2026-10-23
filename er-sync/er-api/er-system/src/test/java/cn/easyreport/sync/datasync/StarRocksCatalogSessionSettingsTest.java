package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** URL and session contract tests; no source or StarRocks server is contacted. */
public class StarRocksCatalogSessionSettingsTest {
    @Test public void starRocksDatetimeDoesNotProbeMysqlSystemTimezoneVariables() throws Exception {
        Fixture f = new Fixture();
        f.datasource.setDatasourceType("STARROCKS");
        f.sourceUrl = "jdbc:mysql://source-fe:9030/erp?serverTimezone=GMT%2B8";
        f.catalogUrl = "jdbc:mysql://source-fe-private:9030";
        f.verify();
        f.verifyTimestamp();
        assertTrue(f.sql.isEmpty());
        f.catalogUrl += "?sessionVariables=time_zone%3D%27UTC%27";
        reject(f, "sessionvariables");
    }

    @Test public void normalConnectionOptionsDoNotRejectOrQueryDatetimeSessions() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?useSSL=true&socketTimeout=30000&useUnicode=true&serverTimezone=GMT%2B8";
        f.catalogUrl += "?useSSL=false&socketTimeout=60000&connectionTimeZone=UTC";
        f.verify();
        assertTrue(f.sql.isEmpty());
    }

    @Test public void differentTimeZoneSessionVariablesAreRejectedBeforeAnySql() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?sessionVariables=time_zone%3D%27%2B08%3A00%27";
        f.catalogUrl += "?sessionVariables=time_zone%3D%27%2B00%3A00%27";
        reject(f, "sessionvariables");
        assertTrue(f.sql.isEmpty());
    }

    @Test public void sourceOnlyAndCatalogOnlySessionOverridesAreRejected() throws Exception {
        Fixture sourceOnly = new Fixture();
        sourceOnly.sourceUrl += "?sessionVariables=time_zone%3D%27%2B08%3A00%27";
        reject(sourceOnly, "sessionvariables");
        Fixture catalogOnly = new Fixture();
        catalogOnly.catalogUrl += "?connectionInitSql=SET%20time_zone%3D%27UTC%27";
        reject(catalogOnly, "connectioninitsql");
    }

    @Test public void actualMetadataUrlIncludesAppendedConnectionSettings() throws Exception {
        Fixture f = new Fixture();
        f.datasource.setConnectionParams("sessionVariables=time_zone%3D%27%2B08%3A00%27");
        f.actualUrl = f.sourceUrl + "?sessionVariables=time_zone%3D%27%2B08%3A00%27";
        reject(f, "sessionvariables");
        assertTrue(f.sql.isEmpty());
    }

    @Test public void conflictingActualConnectionSettingsDoNotFallBackToConfiguredUrl() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?sessionVariables=time_zone%3D%27%2B08%3A00%27";
        f.catalogUrl = f.sourceUrl;
        f.actualUrl = "jdbc:mysql://source:3306/erp?sessionVariables=time_zone%3D%27%2B00%3A00%27";
        reject(f, "实际连接设置与配置不一致");
    }

    @Test public void timezoneAliasesAndExplicitDriverDefaultsAreEquivalent() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?serverTimezone=GMT%2B8&forceConnectionTimeZoneToSession=false&preserveInstants=true";
        f.catalogUrl += "?connectionTimeZone=%2B08%3A00";
        f.verify();
        assertTrue(f.sql.isEmpty());
        f.sessionZone = "+08:00";
        f.globalZone = "+08:00";
        f.verifyTimestamp();
        assertEquals(1, f.sql.size());
    }

    @Test public void explicitlyDifferentDriverTimezonesAreRejected() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?serverTimezone=GMT%2B8";
        f.catalogUrl += "?connectionTimeZone=UTC";
        f.sessionZone = "+08:00";
        f.globalZone = "+08:00";
        f.verify();
        assertTrue(f.sql.isEmpty());
        rejectTimestamp(f, LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 23));
    }

    @Test public void timestampWithoutOverridesUsesSharedServerSession() throws Exception {
        Fixture f = new Fixture();
        f.systemZone = "Asia/Shanghai";
        f.verifyTimestamp();
        assertEquals(1, f.sql.size());
        assertEquals("SELECT @@session.time_zone, @@global.time_zone, @@system_time_zone", f.sql.get(0));
        f = new Fixture();
        f.sessionZone = "+08:00";
        f.globalZone = "+00:00";
        try { f.verifyTimestamp(); fail("Expected divergent source session rejection"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("会话时区与全局时区不同")); }
    }

    @Test public void asymmetricTimestampTimezoneCanOnlyMatchKnownServerZone() throws Exception {
        Fixture known = new Fixture();
        known.sourceUrl += "?serverTimezone=GMT%2B8";
        known.sessionZone = "+08:00";
        known.globalZone = "+08:00";
        known.verifyTimestamp();
        Fixture unknown = new Fixture();
        unknown.sourceUrl += "?serverTimezone=GMT%2B8";
        try { unknown.verifyTimestamp(); fail("Cannot guess SYSTEM timezone"); }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("时区"));
            assertTrue(expected.getMessage().contains("尚未清空目标表"));
        }
    }

    @Test public void tidbSystemShanghaiAndGeneratedGmt8SourceUrlAreEquivalentForModernRows() throws Exception {
        Fixture f = new Fixture();
        f.datasource.setDatasourceType("TIDB");
        f.sourceUrl += "?serverTimezone=GMT%2B8";
        f.systemZone = "Asia/Shanghai";
        f.verifyTimestamp(LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 23));
        assertEquals(1, f.sql.size());
        assertEquals("SELECT @@session.time_zone, @@global.time_zone, @@system_time_zone", f.sql.get(0));
    }

    @Test public void systemSessionAndFixedOffsetGlobalUseActualSystemTimezone() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?serverTimezone=GMT%2B8";
        f.systemZone = "Asia/Shanghai";
        f.globalZone = "+08:00";
        f.verifyTimestamp(LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 23));
        assertEquals(1, f.sql.size());
    }

    @Test public void namedTimezoneAliasesRemainEquivalentIncludingHistoricalDst() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?serverTimezone=PRC";
        f.catalogUrl += "?connectionTimeZone=Asia%2FShanghai";
        f.systemZone = "Asia/Shanghai";
        f.verifyTimestamp(LocalDate.of(1980, 1, 1), LocalDate.of(2026, 10, 23));
        assertEquals(1, f.sql.size());
    }

    @Test public void namedAndFixedOffsetDriverTimezonesCanMatchThroughoutModernRange() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?serverTimezone=GMT%2B8";
        f.catalogUrl += "?connectionTimeZone=Asia%2FShanghai";
        f.systemZone = "Asia/Shanghai";
        f.verifyTimestamp(LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 23));
        assertEquals(1, f.sql.size());
    }

    @Test public void historicalDstCannotBeTreatedAsFixedEightHourOffset() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?serverTimezone=GMT%2B8";
        f.catalogUrl += "?connectionTimeZone=Asia%2FShanghai";
        f.systemZone = "Asia/Shanghai";
        rejectTimestamp(f, LocalDate.of(1986, 1, 1), LocalDate.of(1991, 12, 31));
    }

    @Test public void inheritedSystemTimezoneAlsoChecksHistoricalDataRange() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?serverTimezone=GMT%2B8";
        f.systemZone = "Asia/Shanghai";
        rejectTimestamp(f, LocalDate.of(1986, 1, 1), LocalDate.of(1991, 12, 31));
    }

    @Test public void unknownSystemTimezoneIsNotAssumedToHaveCurrentLocalOffset() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?serverTimezone=GMT%2B8";
        f.systemZone = "unrecognized-system-zone";
        rejectTimestamp(f, LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 23));
    }

    @Test public void matchingExplicitSessionOverridesMayDifferFromGlobalTimezone() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?sessionVariables=time_zone%3D%27%2B08%3A00%27";
        f.catalogUrl = f.sourceUrl;
        f.sessionZone = "+08:00";
        f.globalZone = "+00:00";
        f.verifyTimestamp();
        assertEquals(1, f.sql.size());
    }

    @Test public void forcedTimezoneAndPreserveInstantsMismatchesAreRejected() throws Exception {
        Fixture force = new Fixture();
        force.sourceUrl += "?forceConnectionTimeZoneToSession=true";
        reject(force, "forceconnectiontimezonetosession");
        Fixture preserve = new Fixture();
        preserve.sourceUrl += "?preserveInstants=false";
        reject(preserve, "preserveinstants");
    }

    @Test public void independentUrlCredentialsAreAllowedWithoutComparingConnections() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?user=statistics_user&password=source-secret&password1=source-secret1";
        f.catalogUrl += "?username=import_user&password=catalog-secret&password2=catalog-secret2&password3=catalog-secret3";
        f.verify();
        assertTrue(f.sql.isEmpty());
    }

    @Test public void actualSourceCredentialsMustStillMatchConfiguredCredentialsWithoutPrintingValues() throws Exception {
        Fixture f = new Fixture();
        f.sourceUrl += "?user=statistics_user&password=source-secret";
        f.catalogUrl += "?user=import_user&password=catalog-secret";
        f.actualUrl = "jdbc:mysql://source:3306/erp?user=statistics_user&password=actual-secret";
        try { f.verify(); fail("Expected source configuration mismatch"); }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("实际连接设置与配置不一致"));
            assertFalse(expected.getMessage().contains("source-secret"));
            assertFalse(expected.getMessage().contains("catalog-secret"));
            assertFalse(expected.getMessage().contains("actual-secret"));
            assertTrue(expected.getMessage().contains("尚未清空目标表"));
        }
    }

    @Test public void postgresOptionsAndSqlServerEscapedPropertiesAreCompared() throws Exception {
        Fixture pg = new Fixture();
        pg.datasource.setDatasourceType("POSTGRESQL");
        pg.sourceUrl = "jdbc:postgresql://source:5432/erp?options=-c%20role%3Dread_role";
        pg.catalogUrl = "jdbc:postgresql://source:5432/erp";
        reject(pg, "options");
        Fixture sqlServer = new Fixture();
        sqlServer.datasource.setDatasourceType("SQLSERVER");
        sqlServer.sourceUrl = "jdbc:sqlserver://source:1433;databaseName=erp;password={a;b}}c};encrypt=true";
        sqlServer.catalogUrl = "jdbc:sqlserver://source:1433;databaseName=erp;user=catalog_user;password={other;secret}}};encrypt=false";
        sqlServer.verify();
        assertTrue(sqlServer.sql.isEmpty());
    }

    @Test public void stopIsObservedBeforeMetadataOrQueries() throws Exception {
        Fixture f = new Fixture();
        DataSyncCancellation cancellation = new DataSyncCancellation();
        cancellation.requestStop();
        try {
            try { StarRocksCatalogSessionSettings.verify(f.connection(), f.datasource(), f.properties(), cancellation); fail("Expected stop"); }
            catch (DataSyncCancellation.SyncStoppedException expected) { }
            assertEquals(0, f.metadataCalls);
            assertTrue(f.sql.isEmpty());
        } finally { cancellation.close(); }
    }

    @Test public void timestampStopIsObservedBeforeMetadataOrQueries() throws Exception {
        Fixture f = new Fixture();
        DataSyncCancellation cancellation = new DataSyncCancellation();
        cancellation.requestStop();
        try {
            try {
                StarRocksCatalogSessionSettings.verifyTimestampSession(f.connection(), f.datasource(), f.properties(),
                        cancellation, LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 23));
                fail("Expected stop");
            } catch (DataSyncCancellation.SyncStoppedException expected) { }
            assertEquals(0, f.metadataCalls);
            assertTrue(f.sql.isEmpty());
        } finally { cancellation.close(); }
    }

    private static void reject(Fixture f, String message) throws Exception {
        try { f.verify(); fail("Expected setting mismatch"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }

    private static void rejectTimestamp(Fixture f, LocalDate minDate, LocalDate maxDate) throws Exception {
        try { f.verifyTimestamp(minDate, maxDate); fail("Expected TIMESTAMP timezone mismatch"); }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("时区")
                    || expected.getMessage().contains("connectiontimezone"));
            assertTrue(expected.getMessage().contains("尚未清空目标表"));
        }
    }

    private static final class Fixture {
        final ErDatasource datasource = new ErDatasource();
        String sourceUrl = "jdbc:mysql://source:3306/erp";
        String actualUrl;
        String catalogUrl = "jdbc:mysql://source:3306/erp";
        String sessionZone = "SYSTEM";
        String globalZone = "SYSTEM";
        String systemZone;
        final List<String> sql = new ArrayList<>();
        int metadataCalls;
        Fixture() { datasource.setDatasourceType("MYSQL"); }
        ErDatasource datasource() { datasource.setJdbcUrl(sourceUrl); return datasource; }
        Map<String, String> properties() {
            Map<String, String> properties = new HashMap<>();
            properties.put("jdbc_uri", catalogUrl);
            return properties;
        }
        void verify() throws Exception { StarRocksCatalogSessionSettings.verify(connection(), datasource(), properties(), null); }
        void verifyTimestamp() throws Exception { StarRocksCatalogSessionSettings.verifyTimestampSession(connection(), datasource(), properties(), null); }
        void verifyTimestamp(LocalDate minDate, LocalDate maxDate) throws Exception {
            StarRocksCatalogSessionSettings.verifyTimestampSession(connection(), datasource(), properties(), null, minDate, maxDate);
        }
        Connection connection() {
            return proxy(Connection.class, (method, args) -> {
                if ("getMetaData".equals(method)) {
                    metadataCalls++;
                    return proxy(DatabaseMetaData.class, (name, values) -> {
                        if ("getURL".equals(name)) return actualUrl == null ? sourceUrl : actualUrl;
                        throw new AssertionError(name);
                    });
                }
                if ("createStatement".equals(method)) return proxy(Statement.class, (name, values) -> {
                    if ("executeQuery".equals(name)) {
                        sql.add((String) values[0]);
                        int[] cursor = {-1};
                        return proxy(ResultSet.class, (operation, parameters) -> {
                            if ("next".equals(operation)) return ++cursor[0] == 0;
                            if ("getString".equals(operation)) {
                                int column = (Integer) parameters[0];
                                if (column == 1) return sessionZone;
                                if (column == 2) return globalZone;
                                if (column == 3) return systemZone;
                                throw new AssertionError("Unexpected column: " + column);
                            }
                            if ("close".equals(operation)) return null;
                            throw new AssertionError(operation);
                        });
                    }
                    if ("close".equals(name)) return null;
                    throw new AssertionError(name);
                });
                throw new AssertionError(method);
            });
        }
    }
    private interface Invocation { Object invoke(String method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }
}
