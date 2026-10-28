package cn.easyreport.sync.domain;

import org.junit.Test;

import static org.junit.Assert.*;

public class ErDatasourcePrivacyTest
{
    @Test
    public void diagnosticStringOmitsConnectionAndFreeTextSecretsButKeepsOperationalMetadata()
    {
        ErDatasource source = new ErDatasource();
        source.setDatasourceId(7L);
        source.setDatasourceName("Example source");
        source.setDatasourceType("MYSQL");
        source.setStatus("0");
        source.setHost("private-host.example.invalid");
        source.setDatabaseName("private-database");
        source.setUsername("private-user");
        source.setPassword("private-password");
        source.setJdbcUrl("jdbc:mysql://private-host.example.invalid/demo?password=private-url-secret");
        source.setConnectionParams("password=private-parameter-secret");
        source.setTestMessage("private-driver-message");
        source.setRemark("private-free-text");

        String diagnostic = source.toString();
        assertFalse(diagnostic.contains("private-"));
        assertFalse(diagnostic.contains("jdbc:mysql:"));
        assertTrue(diagnostic.contains("Example source"));
        assertTrue(diagnostic.contains("MYSQL"));
        assertEquals("private-password", source.getPassword());
        assertEquals("private-user", source.getUsername());
        assertTrue(source.getJdbcUrl().contains("private-url-secret"));
    }
}
