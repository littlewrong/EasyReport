package cn.easyreport.sync.starrocks;

import cn.easyreport.sync.domain.ErDatasource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class StarRocksConnectorTest {
    private static final String CREATE_SQL = "CREATE TABLE orders (id INT)";
    private TestDriver driver;
    private StarRocksConnector connector;

    @Before
    public void registerDriver() throws SQLException {
        driver = new TestDriver();
        DriverManager.registerDriver(driver);
    }

    @After
    public void cleanup() throws SQLException {
        if (connector != null) {
            connector.close();
        }
        DriverManager.deregisterDriver(driver);
    }

    @Test
    public void validatesConnectionBeforeEveryOperation() throws Exception {
        ConnectionState state = new ConnectionState();
        open(state);

        for (ReadOperation operation : ReadOperation.values()) {
            assertEquals(operation.expected, operation.read(connector));
        }
        connector.execute(CREATE_SQL);

        assertEquals(Arrays.asList(5, 5, 5, 5), state.validationTimeouts);
        assertEquals(1, driver.openCount);
        assertEquals(Arrays.asList("validate", "tables", "validate", "tables",
                "validate", "query", "validate", "execute"), state.events);
    }

    @Test
    public void replacesConnectionWhenValidationReturnsFalse() throws Exception {
        ConnectionState stale = new ConnectionState();
        stale.valid = false;
        assertValidationReconnects(stale);
    }

    @Test
    public void replacesConnectionWhenValidationThrows() throws Exception {
        ConnectionState stale = new ConnectionState();
        stale.validationFailure = new SQLException("Validation lost connection", "08003");
        assertValidationReconnects(stale);
    }

    @Test
    public void retriesEachReadOnceAfterConnectionFailure() throws Exception {
        for (ReadOperation operation : ReadOperation.values()) {
            ConnectionState stale = new ConnectionState();
            stale.readFailure = new SQLException("Connection closed", "08003");
            ConnectionState replacement = new ConnectionState();
            open(stale, replacement);

            assertEquals(operation.name(), operation.expected, operation.read(connector));
            assertEquals(1, stale.readCount);
            assertEquals(1, replacement.readCount);
            assertEquals(1, stale.closeCount);
            assertEquals(Arrays.asList(5), stale.validationTimeouts);
            assertEquals(2, driver.openCount);
            finishScenario();
        }
    }

    @Test
    public void stopsAfterSecondConnectionFailureForEveryRead() throws Exception {
        for (ReadOperation operation : ReadOperation.values()) {
            ConnectionState first = new ConnectionState();
            first.readFailure = new SQLException("First disconnect", "08003");
            ConnectionState second = new ConnectionState();
            second.readFailure = new SQLException("Second disconnect", "08006");
            open(first, second);

            assertSame(second.readFailure, expectSqlException(() -> operation.read(connector)));
            assertEquals(1, first.readCount);
            assertEquals(1, second.readCount);
            assertEquals(1, first.closeCount);
            assertEquals(1, second.closeCount);
            assertEquals(2, driver.openCount);
            finishScenario();
        }
    }

    @Test
    public void doesNotReplayDdlAndReconnectsForNextOperation() throws Exception {
        ConnectionState stale = new ConnectionState();
        stale.executeFailure = new SQLException("Response lost after DDL", "08006");
        ConnectionState replacement = new ConnectionState();
        open(stale, replacement);

        assertSame(stale.executeFailure, expectSqlException(() -> connector.execute(CREATE_SQL)));
        assertEquals(Collections.singletonList(CREATE_SQL), stale.executedSql);
        assertTrue(replacement.executedSql.isEmpty());
        assertEquals(1, driver.openCount);
        assertEquals(1, stale.closeCount);

        assertTrue(connector.tableExists("erp_quality_a", "orders"));
        assertEquals(2, driver.openCount);
        assertEquals(1, replacement.readCount);
        assertTrue(replacement.executedSql.isEmpty());
    }

    @Test
    public void restoresSuccessfulUseBeforeOperationOnReplacementConnection() throws Exception {
        ConnectionState stale = new ConnectionState();
        ConnectionState replacement = new ConnectionState();
        open(stale, replacement);
        String useSql = "USE `erp_quality_a`";
        connector.execute(useSql);
        stale.valid = false;

        connector.execute(CREATE_SQL);

        assertEquals(Collections.singletonList(useSql), stale.executedSql);
        assertEquals(Arrays.asList(useSql, CREATE_SQL), replacement.executedSql);
        assertEquals(1, stale.closeCount);
        assertEquals(2, driver.openCount);
    }

    @Test
    public void syntaxErrorKeepsConnectionWithoutRetryingDdl() throws Exception {
        ConnectionState state = new ConnectionState();
        state.executeFailure = new SQLException("Syntax error", "42000");
        open(state);

        assertSame(state.executeFailure, expectSqlException(() -> connector.execute(CREATE_SQL)));
        assertEquals(Collections.singletonList(CREATE_SQL), state.executedSql);
        assertEquals(0, state.closeCount);
        assertTrue(connector.tableExists("erp_quality_a", "orders"));
        assertEquals(1, driver.openCount);
        assertEquals(Arrays.asList(5, 5), state.validationTimeouts);
    }

    @Test
    public void closedConnectorNeverOpensAnotherConnection() throws Exception {
        ConnectionState state = new ConnectionState();
        open(state);
        connector.close();

        for (ReadOperation operation : ReadOperation.values()) {
            expectSqlException(() -> operation.read(connector));
        }
        expectSqlException(() -> connector.execute(CREATE_SQL));

        assertEquals(1, driver.openCount);
        assertEquals(1, state.closeCount);
        assertTrue(state.validationTimeouts.isEmpty());
        assertEquals(0, state.readCount);
        assertTrue(state.executedSql.isEmpty());
    }

    private void assertValidationReconnects(ConnectionState stale) throws Exception {
        ConnectionState replacement = new ConnectionState();
        open(stale, replacement);

        assertTrue(connector.tableExists("erp_quality_a", "orders"));

        assertEquals(Arrays.asList(5), stale.validationTimeouts);
        assertEquals(0, stale.readCount);
        assertEquals(1, stale.closeCount);
        assertEquals(1, replacement.readCount);
        assertEquals(2, driver.openCount);
    }

    private void open(ConnectionState... states) throws Exception {
        driver.pending.addAll(Arrays.asList(states));
        ErDatasource datasource = new ErDatasource();
        datasource.setDriverClass(TestDriver.class.getName());
        datasource.setJdbcUrl(driver.url);
        datasource.setUsername("test");
        datasource.setPassword("test");
        connector = new StarRocksConnector(datasource);
    }

    private void finishScenario() {
        connector.close();
        connector = null;
        driver.openCount = 0;
        assertTrue(driver.pending.isEmpty());
    }

    private SQLException expectSqlException(ThrowingAction action) throws Exception {
        try {
            action.run();
            fail("Expected SQLException");
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private enum ReadOperation {
        TABLE_EXISTS(Boolean.TRUE) {
            Object read(StarRocksConnector connector) throws Exception {
                return connector.tableExists("erp_quality_a", "orders");
            }
        },
        LIST_TABLES(Collections.singletonList("orders")) {
            Object read(StarRocksConnector connector) throws Exception {
                return connector.listTables("erp_quality_%", "%");
            }
        },
        GET_CREATE_TABLE(CREATE_SQL) {
            Object read(StarRocksConnector connector) throws Exception {
                return connector.getCreateTableSql("erp_quality_a", "orders");
            }
        };

        final Object expected;

        ReadOperation(Object expected) {
            this.expected = expected;
        }

        abstract Object read(StarRocksConnector connector) throws Exception;
    }

    public static class TestDriver implements Driver {
        final String url = "jdbc:starrocks-connector-test:" + UUID.randomUUID();
        final Deque<ConnectionState> pending = new ArrayDeque<>();
        int openCount;

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) return null;
            openCount++;
            if (pending.isEmpty()) throw new SQLException("Unexpected reconnect");
            return pending.removeFirst().connection();
        }

        @Override public boolean acceptsURL(String url) { return this.url.equals(url); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }

    private static class ConnectionState {
        boolean valid = true;
        boolean closed;
        int closeCount;
        int readCount;
        SQLException validationFailure;
        SQLException readFailure;
        SQLException executeFailure;
        final List<Integer> validationTimeouts = new ArrayList<>();
        final List<String> executedSql = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        Connection connection() {
            return proxy(Connection.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "isClosed": return closed;
                    case "isValid":
                        events.add("validate");
                        validationTimeouts.add((Integer) args[0]);
                        if (validationFailure != null) throw validationFailure;
                        return valid;
                    case "close": closed = true; closeCount++; return null;
                    case "getMetaData": return metadata();
                    case "createStatement": return statement();
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        DatabaseMetaData metadata() {
            return proxy(DatabaseMetaData.class, (object, method, args) -> {
                if ("getTables".equals(method.getName())) {
                    events.add("tables");
                    return read();
                }
                throw new UnsupportedOperationException(method.getName());
            });
        }

        Statement statement() {
            return proxy(Statement.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "execute":
                        events.add("execute");
                        executedSql.add((String) args[0]);
                        if (executeFailure != null) throw executeFailure;
                        return false;
                    case "executeQuery": events.add("query"); return read();
                    case "close": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }

        ResultSet read() throws SQLException {
            readCount++;
            if (readFailure != null) throw readFailure;
            int[] cursor = {0};
            return proxy(ResultSet.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "next": return cursor[0]++ == 0;
                    case "getString": return "Create Table".equals(args[0]) ? CREATE_SQL : "orders";
                    case "close": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(StarRocksConnectorTest.class.getClassLoader(),
                new Class<?>[]{type}, handler));
    }
}
