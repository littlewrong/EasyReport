package cn.easyreport.sync.service.impl;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Driver;

import static org.junit.Assert.*;

public class ErDatasourceKingbaseTest {
    @Test
    public void configuredKingbaseDriverAcceptsGeneratedUrlWithoutConnecting() throws Exception {
        ErDatasource ds = datasource("KINGBASE");
        service().insertErDatasource(ds);
        Driver driver = (Driver) Class.forName(ds.getDriverClass()).getDeclaredConstructor().newInstance();
        assertTrue(driver.acceptsURL(ds.getJdbcUrl()));
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost:5432/report"));
        assertFalse(driver.acceptsURL("jdbc:mysql://localhost:3306/report"));
    }

    @Test
    public void generatesIndependentDriverUrlAndDefaultPort() throws Exception {
        ErDatasource ds = datasource("KINGBASE");
        service().insertErDatasource(ds);

        assertEquals("com.kingbase8.Driver", ds.getDriverClass());
        assertEquals("jdbc:kingbase8://localhost:54321/report?stringtype=unspecified", ds.getJdbcUrl());
    }

    @Test
    public void encodesJsonConnectionParametersAndAllowsStringtypeOverride() throws Exception {
        ErDatasource ds = datasource("KINGBASE");
        ds.setPort(54322);
        ds.setConnectionParams("{\"currentSchema\":\"Sales & Report\",\"stringtype\":\"varchar\"}");
        service().insertErDatasource(ds);

        assertTrue(ds.getJdbcUrl().startsWith("jdbc:kingbase8://localhost:54322/report?"));
        assertTrue(ds.getJdbcUrl().contains("currentSchema=Sales+%26+Report"));
        assertTrue(ds.getJdbcUrl().contains("stringtype=varchar"));
        assertFalse(ds.getJdbcUrl().contains("unspecified"));
    }

    @Test
    public void acceptsQueryStringParametersWithoutDuplicatingStringtype() throws Exception {
        ErDatasource ds = datasource("KINGBASE");
        ds.setConnectionParams("?currentSchema=Sales&stringtype=varchar");
        service().insertErDatasource(ds);
        assertEquals("jdbc:kingbase8://localhost:54321/report?currentSchema=Sales&stringtype=varchar", ds.getJdbcUrl());

        ErDatasource defaults = datasource("KINGBASE");
        defaults.setConnectionParams("currentSchema=Sales");
        service().insertErDatasource(defaults);
        assertTrue(defaults.getJdbcUrl().endsWith("?currentSchema=Sales&stringtype=unspecified"));
    }

    @Test
    public void preservesExplicitConnectionDetailsAndExistingPostgresqlDefaults() throws Exception {
        ErDatasource ds = datasource("KINGBASE");
        ds.setJdbcUrl("jdbc:kingbase8://db:54321/report?currentSchema=Sales");
        ds.setDriverClass("custom.Driver");
        service().insertErDatasource(ds);
        assertEquals("jdbc:kingbase8://db:54321/report?currentSchema=Sales", ds.getJdbcUrl());
        assertEquals("custom.Driver", ds.getDriverClass());

        ErDatasource pg = datasource("POSTGRESQL");
        pg.setPort(5432);
        service().insertErDatasource(pg);
        assertEquals("jdbc:postgresql://localhost:5432/report", pg.getJdbcUrl());
        assertEquals("org.postgresql.Driver", pg.getDriverClass());
    }

    private ErDatasource datasource(String type) {
        ErDatasource ds = new ErDatasource();
        ds.setDatasourceType(type);
        ds.setHost("localhost");
        ds.setDatabaseName("report");
        return ds;
    }

    private ErDatasourceServiceImpl service() throws Exception {
        ErDatasourceServiceImpl service = new ErDatasourceServiceImpl();
        ErDatasourceMapper mapper = (ErDatasourceMapper) Proxy.newProxyInstance(
            ErDatasourceMapper.class.getClassLoader(), new Class<?>[] {ErDatasourceMapper.class},
            (proxy, method, args) -> method.getReturnType() == int.class ? 1 : null);
        Field field = ErDatasourceServiceImpl.class.getDeclaredField("erDatasourceMapper");
        field.setAccessible(true);
        field.set(service, mapper);
        return service;
    }
}
