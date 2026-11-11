package cn.easyreport.sync.datasync;

import cn.easyreport.sync.kingbase.KingbaseJdbcSupport;
import cn.easyreport.sync.strategy.DatabaseStrategyFactory;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Opt-in real KingbaseES V9/JDBC 9 integration test. Use a DEDICATED TEST DATABASE,
 * with permission to create/drop schemas and tables. Do not point this at a production database.
 *
 * Set ER_SYNC_KINGBASE_TEST_JDBC_URL, ER_SYNC_KINGBASE_TEST_USERNAME and
 * ER_SYNC_KINGBASE_TEST_PASSWORD to run. Credentials may alternatively be supplied in the URL.
 * With no URL this test is skipped before loading a driver or opening any connection.
 * All objects belong to a new random schema; rollback and an independent cleanup
 * connection remove that schema even if the tested transaction fails.
 */
public class KingbaseJdbcIntegrationTest {
    private static final List<String> COLUMNS = Arrays.asList("Tenant_Id", "Item_Id", "Changed_At", "Payload");
    private static final List<String> KEYS = Arrays.asList("Tenant_Id", "Item_Id");
    private static final String TABLE = "Order_%Items";

    @Test
    public void realV9SupportsTransferSqlAndExactJdbcMetadata() throws Exception {
        String url = System.getenv("ER_SYNC_KINGBASE_TEST_JDBC_URL");
        Assume.assumeTrue("Set ER_SYNC_KINGBASE_TEST_JDBC_URL to opt in using a dedicated KingbaseES V9 test database",
            url != null && !url.trim().isEmpty());
        Properties properties = new Properties();
        String username = System.getenv("ER_SYNC_KINGBASE_TEST_USERNAME");
        String password = System.getenv("ER_SYNC_KINGBASE_TEST_PASSWORD");
        if (username != null) properties.setProperty("user", username);
        if (password != null) properties.setProperty("password", password);
        Class.forName("com.kingbase8.Driver");

        try (IsolatedDatabase database = new IsolatedDatabase(url, properties)) {
            Connection conn = database.connection;
            assertTrue("This test must connect to KingbaseES", conn.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("kingbase"));
            assertEquals("This integration test validates the KingbaseES V9 baseline", 9,
                conn.getMetaData().getDatabaseMajorVersion());
            conn.setAutoCommit(false);
            database.createSchema();
            String table = qualified(database.schema, TABLE);
            try (Statement statement = conn.createStatement()) {
                statement.execute("CREATE TABLE " + table + " ("
                    + "\"Tenant_Id\" INTEGER NOT NULL, \"Item_Id\" BIGINT GENERATED ALWAYS AS IDENTITY, "
                    + "\"Changed_At\" TIMESTAMP(6), \"Payload\" VARCHAR(128), "
                    + "PRIMARY KEY (\"Tenant_Id\",\"Item_Id\"))");
                // This name matches an unescaped Order_%Items pattern. Its extra
                // column must never leak into the exact getColumns request.
                statement.execute("CREATE TABLE " + qualified(database.schema, "Order_XItems") + " (\"Intruder\" INTEGER)");
                statement.execute("CREATE TABLE " + qualified(database.schema, "Only_Key")
                    + " (\"Id\" BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY)");
            }

            KingbaseDataSyncLoader loader = new KingbaseDataSyncLoader();
            Timestamp start = Timestamp.valueOf("2026-01-01 00:00:00");
            Timestamp changed = Timestamp.valueOf("2026-01-01 01:00:00");
            Timestamp tenantChanged = Timestamp.valueOf("2026-01-01 02:00:00");
            Timestamp end = Timestamp.valueOf("2026-01-02 00:00:00");
            executeRow(conn, loader.buildUpsertSql(table, COLUMNS, false, KEYS), 1, 7001L, start, "original");
            String upsert = loader.buildUpsertSql(table, COLUMNS, true, KEYS);
            executeRow(conn, upsert, 1, 7001L, changed, "updated");
            executeRow(conn, upsert, 2, 7001L, tenantChanged, "other tenant");
            assertEquals(2, count(conn, table));
            try (PreparedStatement statement = conn.prepareStatement("SELECT \"Payload\" FROM " + table
                    + " WHERE \"Tenant_Id\"=? AND \"Item_Id\"=?")) {
                statement.setInt(1, 1);
                statement.setLong(2, 7001L);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("updated", rows.getString(1));
                    assertFalse(rows.next());
                }
            }

            KingbaseDataSyncExecutor executor = new KingbaseDataSyncExecutor(null, null, null);
            try {
                // execute() normally selects this strategy. This isolated test
                // calls the actual JDBC write hook without task/logging mappers.
                Field targetStrategy = DataSyncExecutor.class.getDeclaredField("targetStrategy");
                targetStrategy.setAccessible(true);
                targetStrategy.set(executor, DatabaseStrategyFactory.getStrategy("KINGBASE"));
                executor.executeFullInsertStatement(conn,
                    Arrays.asList(new Object[]{1, 7002L, start, "window start"},
                        new Object[]{1, 7003L, end, "window end"}), 0, 2, table, COLUMNS);
                assertEquals(4, count(conn, table));

                String onlyKey = qualified(database.schema, "Only_Key");
                String onlyKeySql = loader.buildUpsertSql(onlyKey, Collections.singletonList("Id"), true,
                    Collections.singletonList("Id"));
                executeRow(conn, onlyKeySql, 42L);
                executeRow(conn, onlyKeySql, 42L);
                assertEquals(1, count(conn, onlyKey));

                KingbaseDataSyncExtractor extractor = new KingbaseDataSyncExtractor();
                assertEquals(COLUMNS, extractor.listColumns(conn, database.schema, TABLE));
                assertEquals(KEYS, extractor.listPrimaryKeys(conn, database.schema, TABLE));
                conn.setSchema(database.schema);
                List<String[]> matches = extractor.listTableSpecs(conn, Collections.emptyList(),
                    Collections.singletonList(KingbaseJdbcSupport.escapePattern(conn.getMetaData(), TABLE)));
                assertEquals(1, matches.size());
                assertArrayEquals(new String[]{database.schema, TABLE}, matches.get(0));

                try (PreparedStatement statement = conn.prepareStatement(
                        extractor.buildSelectTimestampBounds(database.schema, TABLE, "Changed_At"));
                     ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(start, rows.getTimestamp(1));
                    assertEquals(end, rows.getTimestamp(2));
                }
                try (PreparedStatement statement = conn.prepareStatement(
                        extractor.buildSelectFullWindow(database.schema, TABLE, "Changed_At", KEYS))) {
                    statement.setTimestamp(1, start);
                    statement.setTimestamp(2, end);
                    assertEquals("The time window includes start and excludes end", 3, returnedRows(statement));
                }
                try (PreparedStatement statement = conn.prepareStatement(
                        extractor.buildSelectIncremental(database.schema, TABLE, "Changed_At", KEYS))) {
                    statement.setTimestamp(1, start);
                    assertEquals(3, returnedRows(statement));
                }
                try (PreparedStatement statement = conn.prepareStatement(
                        executor.buildAlignDeleteSelectSql(table, "Item_Id", "Changed_At"))) {
                    executor.setAlignDeleteQueryParams(statement, start, 1);
                    assertEquals("FETCH FIRST must accept the bound row limit", 1, returnedRows(statement));
                }

                executor.clearTable(conn, table);
                assertEquals(0, count(conn, table));
            } finally {
                executor.cancellationContext().close();
            }
        }
    }

    private static void executeRow(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static int returnedRows(PreparedStatement statement) throws SQLException {
        int count = 0;
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) count++;
        }
        return count;
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String qualified(String schema, String table) {
        return KingbaseJdbcSupport.quoteIdentifier(schema) + "." + KingbaseJdbcSupport.quoteIdentifier(table);
    }

    private static final class IsolatedDatabase implements AutoCloseable {
        final Connection connection;
        final String schema = "er_sync_it_" + UUID.randomUUID().toString().replace("-", "");
        private final String url;
        private final Properties properties;
        private boolean schemaCreated;

        IsolatedDatabase(String url, Properties properties) throws SQLException {
            this.url = url;
            this.properties = properties;
            connection = DriverManager.getConnection(url, properties);
        }

        void createSchema() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + KingbaseJdbcSupport.quoteIdentifier(schema));
                schemaCreated = true;
            }
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            try {
                if (!connection.getAutoCommit()) connection.rollback();
            } catch (SQLException e) {
                failure = e;
            }
            try {
                connection.close();
            } catch (SQLException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            if (schemaCreated) {
                // A fresh connection can clean up even if the tested transaction
                // is aborted or DDL was committed by the server. Only the UUID
                // namespace successfully created by this fixture is addressed.
                try (Connection cleanup = DriverManager.getConnection(url, properties);
                     Statement statement = cleanup.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + KingbaseJdbcSupport.quoteIdentifier(schema) + " CASCADE");
                } catch (SQLException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
