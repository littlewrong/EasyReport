package cn.easyreport.sync.datasync;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class DataSyncCancellationTest {
    @Test
    public void stopCancelsBlockedExecutionWithoutBlockingRequester() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            Connection connection = cancellation.wrap(fixture.connection);
            Statement statement = connection.createStatement();
            RawStatement raw = fixture.statements.get(0);
            raw.blockExecute = true;
            raw.blockCancel = true;
            FutureTask<Boolean> worker = async(() -> statement.execute("INSERT SELECT"));
            assertTrue(raw.executeEntered.await(3, TimeUnit.SECONDS));
            FutureTask<Void> stop = async(() -> { cancellation.requestStop(); return null; });
            stop.get(3, TimeUnit.SECONDS);
            assertTrue(raw.cancelEntered.await(3, TimeUnit.SECONDS));
            assertFalse(worker.get(3, TimeUnit.SECONDS));
            raw.cancelRelease.countDown();
        }
    }

    @Test
    public void checkerWatcherCancelsAQueryAlreadyInsideDriver() throws Exception {
        Fixture fixture = new Fixture();
        AtomicBoolean flag = new AtomicBoolean();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            cancellation.start(flag::get);
            Statement statement = cancellation.wrap(fixture.connection).createStatement();
            RawStatement raw = fixture.statements.get(0);
            raw.blockExecute = true;
            FutureTask<Boolean> worker = async(() -> statement.execute("SELECT"));
            assertTrue(raw.executeEntered.await(3, TimeUnit.SECONDS));
            flag.set(true);
            assertTrue(raw.cancelEntered.await(3, TimeUnit.SECONDS));
            assertFalse(worker.get(3, TimeUnit.SECONDS));
            assertTrue(cancellation.isStopRequested());
        }
    }

    @Test
    public void retriesWhenFirstCancelArrivesBeforeDriverStartsExecuting() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            cancellation.start(null);
            Connection connection = cancellation.wrap(fixture.connection);
            Statement statement = connection.createStatement();
            RawStatement raw = fixture.statements.get(0);
            raw.blockExecute = true;
            raw.firstCancelBeforeExecution = true;
            FutureTask<Boolean> worker = async(() -> statement.execute("INSERT SELECT"));
            assertTrue(raw.executeEntered.await(3, TimeUnit.SECONDS));
            cancellation.requestStop();
            assertFalse(worker.get(3, TimeUnit.SECONDS));
            assertTrue("Initial no-op cancellation must be retried", raw.cancels.get() >= 2);
            try (DataSyncCancellation.CleanupScope ignored = cancellation.allowCleanup();
                 Statement cleanup = connection.createStatement()) {
                cleanup.execute("DROP TABLE temporary");
                assertEquals(0, fixture.statements.get(1).cancels.get());
            }
            statement.close();
        }
    }

    @Test
    public void doesNotQueueMoreCancellationCallsWhileOneIsBlocked() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            cancellation.wrap(fixture.connection).createStatement();
            RawStatement raw = fixture.statements.get(0);
            raw.blockCancel = true;
            cancellation.requestStop();
            assertTrue(raw.cancelEntered.await(3, TimeUnit.SECONDS));
            // Exercise several polling ticks without releasing the original driver call.
            assertFalse(raw.cancelRelease.await(450, TimeUnit.MILLISECONDS));
            assertEquals(1, raw.cancels.get());
            raw.cancelRelease.countDown();
        }
    }

    @Test
    public void stopPreventsFurtherExecutionsBatchesCommitsAndStreamingReads() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            Connection connection = cancellation.wrap(fixture.connection);
            PreparedStatement statement = connection.prepareStatement("INSERT");
            CallableStatement callable = connection.prepareCall("CALL test");
            ResultSet rows = statement.executeQuery();
            assertTrue(rows.next());
            assertSame(statement, rows.getStatement());
            assertSame(connection, statement.getConnection());
            cancellation.requestStop();
            stopped(() -> statement.executeUpdate());
            stopped(() -> statement.executeLargeUpdate());
            stopped(() -> statement.executeBatch());
            stopped(() -> statement.addBatch());
            stopped(() -> callable.execute());
            stopped(() -> rows.next());
            stopped(() -> connection.commit());
            stopped(() -> connection.setAutoCommit(true));
            stopped(() -> connection.createStatement());
            assertEquals(1, fixture.statements.get(0).executions.get());
            assertEquals(1, fixture.statements.get(0).nexts.get());
            assertEquals(0, fixture.commits.get());
            connection.rollback();
            rows.close();
            statement.close();
            callable.close();
            assertEquals(1, fixture.rollbacks.get());
        }
    }

    @Test
    public void cleanupWaitsForExistingCancelAndCanExecuteAfterStop() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            Connection connection = cancellation.wrap(fixture.connection);
            connection.createStatement();
            RawStatement original = fixture.statements.get(0);
            original.blockCancel = true;
            cancellation.requestStop();
            assertTrue(original.cancelEntered.await(3, TimeUnit.SECONDS));
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            CountDownLatch cleanupFinished = new CountDownLatch(1);
            FutureTask<Void> cleanup = async(() -> {
                cleanupStarted.countDown();
                try (DataSyncCancellation.CleanupScope ignored = cancellation.allowCleanup();
                     Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE temporary");
                    connection.commit();
                    cancellation.requestStop();
                }
                cleanupFinished.countDown();
                return null;
            });
            assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS));
            assertFalse(cleanupFinished.await(100, TimeUnit.MILLISECONDS));
            original.cancelRelease.countDown();
            cleanup.get(3, TimeUnit.SECONDS);
            assertEquals(1, fixture.statements.get(1).executions.get());
            assertEquals(0, fixture.statements.get(1).cancels.get());
            assertEquals(1, fixture.commits.get());
            stopped(() -> connection.commit());
        }
    }

    @Test
    public void stopDuringCleanupCannotCancelCleanupStatement() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            Connection connection = cancellation.wrap(fixture.connection);
            try (DataSyncCancellation.CleanupScope ignored = cancellation.allowCleanup();
                 Statement statement = connection.createStatement()) {
                cancellation.requestStop();
                statement.execute("DROP TABLE temporary");
                assertEquals(0, fixture.statements.get(0).cancels.get());
            }
            assertEquals(0, fixture.statements.get(0).cancels.get());
            stopped(cancellation::checkStopped);
        }
    }

    @Test
    public void statementRemainsRegisteredWhileStreamingResultIsOpen() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            Statement statement = cancellation.wrap(fixture.connection).createStatement();
            ResultSet rows = statement.executeQuery("SELECT");
            cancellation.requestStop();
            assertTrue(fixture.statements.get(0).cancelEntered.await(3, TimeUnit.SECONDS));
            stopped(rows::next);
            rows.close();
            statement.close();
        }
    }

    @Test
    public void streamingResultAndStatementCloseWaitForTheirPendingCancel() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            Statement statement = cancellation.wrap(fixture.connection).createStatement();
            ResultSet rows = statement.executeQuery("SELECT");
            RawStatement raw = fixture.statements.get(0);
            raw.blockCancel = true;
            cancellation.requestStop();
            assertTrue(raw.cancelEntered.await(3, TimeUnit.SECONDS));
            FutureTask<Void> closeRows = async(() -> { rows.close(); return null; });
            FutureTask<Void> closeStatement = async(() -> { statement.close(); return null; });
            assertFalse(raw.rowsCloseEntered.await(100, TimeUnit.MILLISECONDS));
            assertFalse(raw.statementCloseEntered.await(100, TimeUnit.MILLISECONDS));
            raw.cancelRelease.countDown();
            closeRows.get(3, TimeUnit.SECONDS);
            closeStatement.get(3, TimeUnit.SECONDS);
            assertTrue(raw.cancels.get() >= 1);
            assertEquals(0, raw.rowsCloseEntered.getCount());
            assertEquals(0, raw.statementCloseEntered.getCount());
        }
    }

    @Test
    public void closedStatementsAndOtherTasksAreNeverCancelled() throws Exception {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        try (DataSyncCancellation a = new DataSyncCancellation();
             DataSyncCancellation b = new DataSyncCancellation()) {
            Statement closed = a.wrap(first.connection).createStatement();
            closed.close();
            Statement active = b.wrap(second.connection).createStatement();
            a.requestStop();
            try (DataSyncCancellation.CleanupScope ignored = a.allowCleanup()) {
                assertEquals(0, first.statements.get(0).cancels.get());
                assertEquals(0, second.statements.get(0).cancels.get());
            }
            assertFalse(active.execute("SELECT"));
            assertFalse(b.isStopRequested());
        }
    }

    @Test
    public void pendingStopIsNotLostByStartAndCloseIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        DataSyncCancellation cancellation = new DataSyncCancellation();
        cancellation.wrap(fixture.connection);
        cancellation.requestStop();
        cancellation.start(() -> false);
        assertTrue(cancellation.isStopRequested());
        stopped(cancellation::checkStopped);
        cancellation.close();
        cancellation.close();
        assertEquals(0, fixture.closes.get());
    }

    @Test
    public void checkerFailureDoesNotStopOrReplaceDriverSQLException() throws Exception {
        Fixture fixture = new Fixture();
        try (DataSyncCancellation cancellation = new DataSyncCancellation()) {
            cancellation.start(() -> { throw new IllegalStateException("status unavailable"); });
            Statement statement = cancellation.wrap(fixture.connection).createStatement();
            assertFalse(cancellation.isStopRequested());
            SQLException original = new SQLException("driver failure", "42000", 42);
            fixture.statements.get(0).failure = original;
            try {
                statement.execute("bad SQL");
                fail("Expected original JDBC exception");
            } catch (SQLException error) {
                assertSame(original, error);
            }
        }
    }

    private static void stopped(CheckedRunnable action) throws Exception {
        try {
            action.run();
            fail("Stopped operation reached JDBC driver");
        } catch (DataSyncCancellation.SyncStoppedException expected) {
            assertEquals("57014", expected.getSQLState());
        }
    }

    private interface CheckedRunnable { void run() throws Exception; }

    private static <T> FutureTask<T> async(java.util.concurrent.Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        Thread thread = new Thread(task, "cancellation-test");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    private static final class Fixture {
        final List<RawStatement> statements = new ArrayList<>();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final Connection connection = proxy(Connection.class, (object, method, args) -> {
            String name = method.getName();
            if ("createStatement".equals(name) || "prepareStatement".equals(name) || "prepareCall".equals(name)) {
                RawStatement statement = new RawStatement();
                statements.add(statement);
                return statement.proxy;
            }
            if ("commit".equals(name)) { commits.incrementAndGet(); }
            if ("rollback".equals(name)) { rollbacks.incrementAndGet(); }
            if ("close".equals(name)) { closes.incrementAndGet(); }
            return defaultValue(method.getReturnType());
        });
    }

    private static final class RawStatement {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger nexts = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final CountDownLatch executeEntered = new CountDownLatch(1);
        final CountDownLatch executeRelease = new CountDownLatch(1);
        final CountDownLatch cancelEntered = new CountDownLatch(1);
        final CountDownLatch cancelRelease = new CountDownLatch(1);
        final CountDownLatch rowsCloseEntered = new CountDownLatch(1);
        final CountDownLatch statementCloseEntered = new CountDownLatch(1);
        volatile boolean blockExecute;
        volatile boolean blockCancel;
        volatile boolean firstCancelBeforeExecution;
        volatile SQLException failure;
        final ResultSet rows = proxy(ResultSet.class, (object, method, args) -> {
            if ("next".equals(method.getName())) {
                nexts.incrementAndGet();
                return true;
            }
            if ("close".equals(method.getName())) { rowsCloseEntered.countDown(); }
            return defaultValue(method.getReturnType());
        });
        final CallableStatement proxy = proxy(CallableStatement.class, (object, method, args) -> {
            String name = method.getName();
            if ("close".equals(name)) { statementCloseEntered.countDown(); }
            if ("cancel".equals(name)) {
                int attempt = cancels.incrementAndGet();
                cancelEntered.countDown();
                if (firstCancelBeforeExecution && attempt == 1) {
                    return defaultValue(method.getReturnType());
                }
                executeRelease.countDown();
                if (blockCancel && !cancelRelease.await(5, TimeUnit.SECONDS)) {
                    throw new SQLException("Timed out waiting for test cancellation release");
                }
            }
            if (name.startsWith("execute")) {
                executions.incrementAndGet();
                executeEntered.countDown();
                if (firstCancelBeforeExecution && !cancelEntered.await(3, TimeUnit.SECONDS)) {
                    throw new SQLException("Timed out waiting for initial no-op cancellation");
                }
                if (blockExecute && !executeRelease.await(5, TimeUnit.SECONDS)) {
                    throw new SQLException("Timed out waiting for test execution release");
                }
                if (failure != null) { throw failure; }
                if ("executeQuery".equals(name)) { return rows; }
                if ("executeBatch".equals(name)) { return new int[0]; }
                if ("executeLargeBatch".equals(name)) { return new long[0]; }
            }
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(DataSyncCancellationTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) { return false; }
        if (type == int.class) { return 0; }
        if (type == long.class) { return 0L; }
        if (type == short.class) { return (short) 0; }
        if (type == byte.class) { return (byte) 0; }
        if (type == float.class) { return 0F; }
        if (type == double.class) { return 0D; }
        if (type == char.class) { return (char) 0; }
        return null;
    }
}
