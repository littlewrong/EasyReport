package cn.easyreport.sync.datasync;

import cn.easyreport.sync.dm.DmJdbcSupport;
import cn.easyreport.sync.strategy.DatabaseStrategyFactory;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
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
 * Opt-in DM8+ integration test. Use a dedicated TEST DATABASE/account with CREATE TABLE permission.
 * Configure ER_SYNC_DM_TEST_JDBC_URL, ER_SYNC_DM_TEST_USERNAME, ER_SYNC_DM_TEST_PASSWORD.
 * Without a URL the test skips before loading the driver or connecting. It only creates and
 * removes its own UUID-named tables in the connection's default schema, never existing tables.
 */
public class DmJdbcIntegrationTest {
    private static final List<String> COLUMNS = Arrays.asList("Tenant_Id", "Item_Id", "Changed_At", "Payload");
    private static final List<String> KEYS = Arrays.asList("Tenant_Id", "Item_Id");

    @Test
    public void realDmSupportsMergeBulkInsertIdentityPreservationAndJdbcReads() throws Exception {
        String url = System.getenv("ER_SYNC_DM_TEST_JDBC_URL");
        Assume.assumeTrue("Set ER_SYNC_DM_TEST_JDBC_URL to opt in using a dedicated DM8+ test database",
            url != null && !url.trim().isEmpty());
        Properties properties = new Properties();
        String username = System.getenv("ER_SYNC_DM_TEST_USERNAME");
        String password = System.getenv("ER_SYNC_DM_TEST_PASSWORD");
        if (username != null) properties.setProperty("user", username);
        if (password != null) properties.setProperty("password", password);
        Class.forName("dm.jdbc.driver.DmDriver");
        try (TestDatabase database = new TestDatabase(url, properties)) {
            Connection conn = database.connection;
            assertTrue("Use a DM database for this test", conn.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("dm"));
            assertTrue("The SQL baseline is DM8+", conn.getMetaData().getDatabaseMajorVersion() >= 8);
            conn.setAutoCommit(false);
            String tableName = database.prefix + "_Order_%Items";
            String table = database.createTable(tableName,
                "\"Tenant_Id\" INTEGER NOT NULL, \"Item_Id\" BIGINT NOT NULL, \"Changed_At\" TIMESTAMP(6), "
                    + "\"Payload\" VARCHAR(128), PRIMARY KEY (\"Tenant_Id\",\"Item_Id\")");
            database.createTable(database.prefix + "_Order_XItems", "\"Intruder\" INTEGER");
            String identity = database.createTable(database.prefix + "_Identity",
                "\"Id\" BIGINT IDENTITY(1,1), \"Payload\" VARCHAR(32) PRIMARY KEY, \"Note\" VARCHAR(32)");
            DmDataSyncLoader loader = new DmDataSyncLoader();
            Timestamp start = Timestamp.valueOf("2026-01-01 00:00:00");
            Timestamp changed = Timestamp.valueOf("2026-01-01 01:00:00");
            Timestamp end = Timestamp.valueOf("2026-01-02 00:00:00");
            write(conn, loader.buildUpsertSql(table, COLUMNS, false, KEYS), 1, 7L, start, "old");
            String merge = loader.buildUpsertSql(table, COLUMNS, true, KEYS);
            try (PreparedStatement statement = conn.prepareStatement(merge)) {
                bind(statement, 1, 7L, changed, "updated"); statement.addBatch();
                bind(statement, 2, 7L, changed, "other tenant"); statement.addBatch();
                statement.executeBatch();
            }
            assertEquals(2, count(conn, table));
            try (PreparedStatement statement = conn.prepareStatement("SELECT \"Payload\" FROM " + table
                    + " WHERE \"Tenant_Id\"=? AND \"Item_Id\"=?")) {
                bind(statement, 1, 7L);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next()); assertEquals("updated", rows.getString(1)); assertFalse(rows.next());
                }
            }
            DmDataSyncExecutor executor = new DmDataSyncExecutor(null, null, null);
            try {
                Field strategy = DataSyncExecutor.class.getDeclaredField("targetStrategy");
                strategy.setAccessible(true); strategy.set(executor, DatabaseStrategyFactory.getStrategy("DM"));
                executor.executeFullInsertStatement(conn,
                    Arrays.asList(new Object[]{1, 8L, start, "included"}, new Object[]{1, 9L, end, "excluded"}),
                    0, 2, table, COLUMNS);
                assertEquals(4, count(conn, table));
                executor.beforeBatchInsert(conn, identity);
                try {
                    List<String> identityColumns = Arrays.asList("Id", "Payload", "Note");
                    write(conn, loader.buildUpsertSql(identity, identityColumns, false), 7001L, "kept id", "old");
                    loader.prepareTarget(conn, identity);
                    String identityMerge = loader.buildUpsertSql(identity, identityColumns, true,
                        Collections.singletonList("Payload"));
                    write(conn, identityMerge, 9000L, "kept id", "updated");
                    write(conn, identityMerge, 9001L, "new id", "inserted");
                    try (Statement statement = conn.createStatement();
                         ResultSet rows = statement.executeQuery("SELECT \"Id\",\"Note\" FROM " + identity
                             + " WHERE \"Payload\"='kept id'")) {
                        assertTrue(rows.next()); assertEquals(7001L, rows.getLong(1)); assertEquals("updated", rows.getString(2));
                    }
                    conn.commit();
                } finally {
                    executor.afterBatchInsert(conn, identity);
                }
                write(conn, "INSERT INTO " + identity + "(\"Payload\") VALUES (?)", "generated id");
                assertEquals(3, count(conn, identity));

                DmDataSyncExtractor extractor = new DmDataSyncExtractor();
                assertEquals(COLUMNS, extractor.listColumns(conn, database.schema, tableName));
                assertEquals(KEYS, extractor.listPrimaryKeys(conn, database.schema, tableName));
                List<String[]> tables = extractor.listTableSpecs(conn, Collections.emptyList(),
                    Collections.singletonList(DmJdbcSupport.escapePattern(conn.getMetaData(), tableName)));
                assertEquals(1, tables.size());
                assertArrayEquals(new String[]{database.schema, tableName}, tables.get(0));
                try (PreparedStatement statement = conn.prepareStatement(
                        extractor.buildSelectFullWindow(database.schema, tableName, "Changed_At", KEYS),
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    statement.setFetchSize(2); bind(statement, start, end);
                    assertEquals(3, returnedRows(statement));
                }
                try (PreparedStatement statement = conn.prepareStatement(
                        extractor.buildSelectIncremental(database.schema, tableName, "Changed_At", KEYS))) {
                    bind(statement, start); assertEquals(3, returnedRows(statement));
                }
                try (PreparedStatement statement = conn.prepareStatement(
                        executor.buildAlignDeleteSelectSql(table, "Item_Id", "Changed_At"))) {
                    executor.setAlignDeleteQueryParams(statement, start, 1);
                    assertEquals(1, returnedRows(statement));
                }
                executor.clearTable(conn, table);
                assertEquals(0, count(conn, table));
            } finally { executor.cancellationContext().close(); }
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    private static void write(Connection conn, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) { bind(statement, values); statement.executeUpdate(); }
    }

    private static int returnedRows(PreparedStatement statement) throws SQLException {
        int count = 0;
        try (ResultSet rows = statement.executeQuery()) { while (rows.next()) count++; }
        return count;
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement statement = conn.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    private static final class TestDatabase implements AutoCloseable {
        final Connection connection;
        final String schema;
        final String prefix = "er_sync_it_" + UUID.randomUUID().toString().replace("-", "");
        private final List<String> created = new ArrayList<>();
        private final String url;
        private final Properties properties;

        TestDatabase(String url, Properties properties) throws SQLException {
            this.url = url; this.properties = properties;
            connection = DriverManager.getConnection(url, properties);
            try { schema = DmJdbcSupport.currentSchema(connection); }
            catch (SQLException | RuntimeException e) { connection.close(); throw e; }
        }

        String createTable(String name, String columns) throws SQLException {
            String table = DmJdbcSupport.quoteIdentifier(schema) + "." + DmJdbcSupport.quoteIdentifier(name);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + table + " (" + columns + ")");
                created.add(table);
            }
            return table;
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            try { if (!connection.getAutoCommit()) connection.rollback(); }
            catch (SQLException e) { failure = e; }
            try { connection.close(); }
            catch (SQLException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            if (!created.isEmpty()) {
                try (Connection cleanup = DriverManager.getConnection(url, properties); Statement statement = cleanup.createStatement()) {
                    Collections.reverse(created);
                    for (String table : created) {
                        try { statement.execute("DROP TABLE IF EXISTS " + table); }
                        catch (SQLException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
                    }
                } catch (SQLException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
        }
    }
}
