package cn.easyreport.sync.datasync;

import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Read-only compatibility check against a real StarRocks server. Opt in with
 * ER_SYNC_STARROCKS_TEST_JDBC_URL, ER_SYNC_STARROCKS_TEST_USERNAME and
 * ER_SYNC_STARROCKS_TEST_PASSWORD. Does not create tables or read business data.
 */
public class StarRocksJsonProjectionJdbcTest {
    @Test
    public void generatedJsonProbePreservesScalarsAndDistinguishesJsonNullFromSqlNull() throws Exception {
        String url = System.getenv("ER_SYNC_STARROCKS_TEST_JDBC_URL");
        assumeTrue("Set ER_SYNC_STARROCKS_TEST_JDBC_URL to check a real StarRocks server",
                url != null && !url.trim().isEmpty());
        String username = System.getenv("ER_SYNC_STARROCKS_TEST_USERNAME");
        String password = System.getenv("ER_SYNC_STARROCKS_TEST_PASSWORD");
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            try (ResultSet rows = statement.executeQuery(StarRocksExternalFullImport.JSON_CONVERSION_PROBE_SQL)) {
                assertTrue("JSON probe must return a row", rows.next());
                assertEquals("123", rows.getString(1));
                assertEquals("true", rows.getString(2));
                assertEquals("JSON null must serialize as text", "null", rows.getString(3));
                assertFalse(rows.wasNull());
                assertNull("SQL NULL must remain SQL NULL", rows.getString(4));
                assertTrue(rows.wasNull());
                assertFalse(rows.next());
            }
        }
    }
}
