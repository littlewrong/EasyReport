package cn.easyreport.sync.service.impl;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.util.Properties;

import static org.junit.Assert.*;

public class ErDatasourceDmTest {
    @Test
    public void generatesRealDmDriverUrlAndAllowsDefaultSchemaToBeOmitted() throws Exception {
        ErDatasource ds = datasource();
        service().insertErDatasource(ds);
        assertEquals("DM", ds.getDatasourceType());
        assertEquals("", ds.getDatabaseName());
        assertEquals("dm.jdbc.driver.DmDriver", ds.getDriverClass());
        assertEquals("jdbc:dm://localhost:5236?convertToTimestamp=true", ds.getJdbcUrl());
        Driver driver = (Driver) Class.forName(ds.getDriverClass()).getDeclaredConstructor().newInstance();
        assertTrue(driver.acceptsURL(ds.getJdbcUrl()));
        assertFalse(driver.acceptsURL("jdbc:oracle:thin:@localhost:1521:test"));
    }

    @Test
    public void preservesSchemaAndJsonParametersUsingTheRealDriversUrlSyntax() throws Exception {
        ErDatasource ds = datasource();
        ds.setDatabaseName("Sales_East");
        ds.setPort(5237);
        ds.setConnectionParams("{\"schema\":\"Quoted Schema\",\"convertToTimestamp\":false,\"connectTimeout\":8000}");
        service().insertErDatasource(ds);
        assertTrue(ds.getJdbcUrl().startsWith("jdbc:dm://localhost:5237?"));
        assertTrue(ds.getJdbcUrl().contains("schema=Quoted Schema"));
        assertTrue(ds.getJdbcUrl().contains("convertToTimestamp=false"));
        assertTrue(ds.getJdbcUrl().contains("connectTimeout=8000"));
        assertFalse(ds.getJdbcUrl().contains("Sales"));
        Driver driver = new dm.jdbc.driver.DmDriver();
        boolean foundSchema = false;
        for (DriverPropertyInfo property : driver.getPropertyInfo(ds.getJdbcUrl(), new Properties())) {
            if ("schema".equalsIgnoreCase(property.name)) {
                foundSchema = true;
                assertEquals("Quoted Schema", property.value);
            }
        }
        assertTrue("The driver must parse the exact schema rather than retaining URL escape text", foundSchema);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsASeparatorInsideAJsonPropertyRatherThanGeneratingABrokenDmUrl() throws Exception {
        ErDatasource ds = datasource();
        ds.setConnectionParams("{\"schema\":\"Sales&Report\"}");
        service().insertErDatasource(ds);
    }

    @Test
    public void queryStringParametersOverrideDefaultsAndKeepExactSchemaCase() throws Exception {
        ErDatasource ds = datasource();
        ds.setDatabaseName("Sales_East");
        ds.setConnectionParams("?connectTimeout=8000&convertToTimestamp=false");
        service().insertErDatasource(ds);
        assertEquals("jdbc:dm://localhost:5236?connectTimeout=8000&convertToTimestamp=false&schema=Sales_East", ds.getJdbcUrl());
    }

    @Test
    public void preservesExplicitUrlAndDriver() throws Exception {
        ErDatasource ds = datasource();
        ds.setJdbcUrl("jdbc:dm://custom-service?schema=ExactSchema");
        ds.setDriverClass("custom.Driver");
        service().insertErDatasource(ds);
        assertEquals("jdbc:dm://custom-service?schema=ExactSchema", ds.getJdbcUrl());
        assertEquals("custom.Driver", ds.getDriverClass());
    }

    @Test
    public void blankDmSchemaIsPersistedForTheExistingNotNullColumnWithoutChangingOtherTypes() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ErDatasource", ErDatasource.class);
        String resource = "mapper/sync/ErDatasourceMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        ErDatasource ds = datasource();
        service().insertErDatasource(ds);
        String namespace = "cn.easyreport.sync.mapper.ErDatasourceMapper.";
        for (String operation : new String[]{"insertErDatasource", "updateErDatasource"}) {
            BoundSql sql = configuration.getMappedStatement(namespace + operation).getBoundSql(ds);
            assertTrue(sql.getSql().contains("database_name"));
            assertTrue(sql.getParameterMappings().stream().anyMatch(p -> "databaseName".equals(p.getProperty())));
            ds.setDatasourceType("POSTGRESQL");
            assertFalse(configuration.getMappedStatement(namespace + operation).getBoundSql(ds).getSql().contains("database_name"));
            ds.setDatasourceType("DM");
        }
    }

    private ErDatasource datasource() {
        ErDatasource ds = new ErDatasource();
        ds.setDatasourceType("dm");
        ds.setHost("localhost");
        return ds;
    }

    private ErDatasourceServiceImpl service() throws Exception {
        ErDatasourceServiceImpl service = new ErDatasourceServiceImpl();
        ErDatasourceMapper mapper = (ErDatasourceMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ErDatasourceMapper.class}, (proxy, method, args) -> method.getReturnType() == int.class ? 1 : null);
        Field field = ErDatasourceServiceImpl.class.getDeclaredField("erDatasourceMapper");
        field.setAccessible(true);
        field.set(service, mapper);
        return service;
    }
}
