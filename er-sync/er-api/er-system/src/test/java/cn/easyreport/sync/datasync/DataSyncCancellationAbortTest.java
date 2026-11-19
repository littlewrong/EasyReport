package cn.easyreport.sync.datasync;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** A stalled JDBC cancel must not strand a stopped worker or reach its replacement cleanup session. */
public class DataSyncCancellationAbortTest {
    @Test
    public void blockedCancelEscalatesOnlyItsOriginalConnectionAndCleanupUsesAnIsolatedSession() throws Exception {
        Driver original = new Driver(Abort.SYNCHRONOUS);
        Driver cleanup = new Driver(Abort.SYNCHRONOUS);
        Driver unrelated = new Driver(Abort.SYNCHRONOUS);
        AtomicInteger replacements = new AtomicInteger();
        try (DataSyncCancellation cancellation = new DataSyncCancellation(25);
             DataSyncCancellation otherTask = new DataSyncCancellation(25)) {
            Connection connection = cancellation.wrap(original.connection, () -> {
                replacements.incrementAndGet();
                return cleanup.connection;
            });
            Statement oldStatement = connection.createStatement();
            Statement otherStatement = otherTask.wrap(unrelated.connection).createStatement();
            original.blockExecution = true;
            FutureTask<Boolean> query = async(() -> oldStatement.execute("INSERT SELECT"));
            assertTrue(original.executionEntered.await(3, TimeUnit.SECONDS));
            cancellation.requestStop();
            assertTrue(original.cancelEntered.await(3, TimeUnit.SECONDS));
            FutureTask<Void> finalizer = cleanup(cancellation, connection, oldStatement);
            finalizer.get(3, TimeUnit.SECONDS);
            assertFalse(query.get(3, TimeUnit.SECONDS));
            assertTrue(original.closed.get());
            assertEquals(1, original.aborts.get());
            assertEquals(0, original.closeCalls.get());
            assertEquals(1, replacements.get());
            assertEquals(1, cleanup.executions.get());
            assertEquals(1, cleanup.commits.get());
            assertEquals(1, cleanup.closeCalls.get());
            assertEquals(0, cleanup.cancels.get());
            assertEquals(0, unrelated.aborts.get());
            assertEquals(0, unrelated.cancels.get());
            assertFalse(otherStatement.execute("SELECT still running"));
            // The original driver is still inside cancel. Releasing that delayed call
            // after DROP/commit must not affect the independent cleanup connection.
            assertEquals(1, original.cancelRelease.getCount());
            original.cancelRelease.countDown();
            assertEquals(1, cleanup.executions.get());
            stopped(connection::commit);
        } finally {
            original.release();
        }
    }

    @Test
    public void abortReturningBeforeItsExecutorTaskFinishesDoesNotReleaseCleanup() throws Exception {
        Driver original = new Driver(Abort.ASYNCHRONOUS);
        Driver replacement = new Driver(Abort.SYNCHRONOUS);
        AtomicInteger replacements = new AtomicInteger();
        try (DataSyncCancellation cancellation = new DataSyncCancellation(25)) {
            Connection connection = cancellation.wrap(original.connection, () -> {
                replacements.incrementAndGet();
                return replacement.connection;
            });
            Statement statement = connection.createStatement();
            cancellation.requestStop();
            assertTrue(original.cancelEntered.await(3, TimeUnit.SECONDS));
            FutureTask<Void> finalizer = cleanup(cancellation, connection, statement);
            assertTrue(original.abortTaskEntered.await(3, TimeUnit.SECONDS));
            original.cancelRelease.countDown();
            assertTrue(original.cancelCompleted.await(3, TimeUnit.SECONDS));
            assertFalse(finalizer.isDone());
            assertEquals(0, replacements.get());
            assertFalse(original.closed.get());
            original.abortTaskRelease.countDown();
            finalizer.get(3, TimeUnit.SECONDS);
            assertTrue(original.closed.get());
            assertEquals(1, replacements.get());
            assertEquals(1, replacement.executions.get());
            assertEquals(0, replacement.cancels.get());
        } finally {
            original.release();
        }
    }

    @Test
    public void unsupportedOrUnconfirmedAbortCannotPretendThatTheConnectionTerminated() throws Exception {
        for (Abort behavior : new Abort[]{Abort.UNSUPPORTED, Abort.NO_OP}) {
            Driver original = new Driver(behavior);
            AtomicInteger replacements = new AtomicInteger();
            try (DataSyncCancellation cancellation = new DataSyncCancellation(25)) {
                Connection connection = cancellation.wrap(original.connection, () -> {
                    replacements.incrementAndGet();
                    return new Driver(Abort.SYNCHRONOUS).connection;
                });
                Statement statement = connection.createStatement();
                cancellation.requestStop();
                assertTrue(original.cancelEntered.await(3, TimeUnit.SECONDS));
                FutureTask<Void> finalizer = cleanup(cancellation, connection, statement);
                assertTrue(original.abortInvoked.await(3, TimeUnit.SECONDS));
                assertFalse("Unconfirmed abort must retain the original pending cancellation", finalizer.isDone());
                assertEquals(0, replacements.get());
                assertFalse(original.closed.get());
                original.cancelRelease.countDown();
                finalizer.get(3, TimeUnit.SECONDS);
                assertEquals(0, replacements.get());
                assertEquals(1, original.executions.get());
                assertEquals(1, original.closeCalls.get());
            } finally {
                original.release();
            }
        }
    }

    @Test
    public void failedOrNoOpAbortStillAllowsSubsequentCancellationAttempts() throws Exception {
        for (Abort behavior : new Abort[]{Abort.UNSUPPORTED, Abort.NO_OP}) {
            Driver original = new Driver(behavior);
            original.cancelRelease.countDown();
            original.blockExecution = true;
            original.releaseExecutionOnlyAfterAbort = true;
            try (DataSyncCancellation cancellation = new DataSyncCancellation(25)) {
                Connection connection = cancellation.wrap(original.connection);
                Statement statement = connection.createStatement();
                FutureTask<Boolean> query = async(() -> statement.execute("INSERT SELECT"));
                assertTrue(original.executionEntered.await(3, TimeUnit.SECONDS));
                cancellation.requestStop();
                assertTrue(original.abortInvoked.await(3, TimeUnit.SECONDS));
                cancellation.requestStop();
                assertFalse(query.get(3, TimeUnit.SECONDS));
                assertTrue("A failed abort must not disable later Statement.cancel calls", original.cancels.get() >= 2);
                assertFalse("The cancel retry, not an unconfirmed abort, ended execution", original.closed.get());
                statement.close();
                connection.close();
            } finally {
                original.release();
            }
        }
    }

    @Test
    public void cleanupAlreadyUsingTheOriginalSessionIsNotAbortedByTheStopTimer() throws Exception {
        Driver original = new Driver(Abort.SYNCHRONOUS);
        original.cancelRelease.countDown();
        try (DataSyncCancellation cancellation = new DataSyncCancellation(0)) {
            Connection connection = cancellation.wrap(original.connection);
            try (DataSyncCancellation.CleanupScope ignored = cancellation.allowCleanup();
                 Statement statement = connection.createStatement()) {
                cancellation.requestStop();
                // Explicit watcher ticks exercise the overdue check without a timing-dependent sleep.
                for (int count = 0; count < 5; count++) cancellation.requestStop();
                assertFalse(statement.execute("DROP TABLE temporary"));
                connection.commit();
                assertEquals(0, original.aborts.get());
                assertEquals(0, original.cancels.get());
            }
        } finally {
            original.release();
        }
    }

    @Test
    public void cleanupConnectionTimeoutLetsTheWorkerExitAndClosesALateSessionWithoutExecutingSql() throws Exception {
        Driver original = new Driver(Abort.SYNCHRONOUS);
        Driver lateConnection = new Driver(Abort.SYNCHRONOUS);
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        try (DataSyncCancellation cancellation = new DataSyncCancellation(25, 25)) {
            Connection connection = cancellation.wrap(original.connection, () -> {
                attempts.incrementAndGet();
                openEntered.countDown();
                if (!releaseOpen.await(5, TimeUnit.SECONDS)) throw new SQLException("Connection test timed out");
                return lateConnection.connection;
            });
            Statement statement = connection.createStatement();
            cancellation.requestStop();
            assertTrue(original.cancelEntered.await(3, TimeUnit.SECONDS));
            FutureTask<SQLException> worker = async(() -> {
                try {
                    try (DataSyncCancellation.CleanupScope ignored = cancellation.allowCleanup();
                         Statement cleanup = connection.createStatement()) {
                        cleanup.execute("DROP TABLE temporary");
                    }
                    return null;
                } catch (SQLException failure) {
                    return failure;
                } finally {
                    statement.close();
                    connection.close();
                }
            });
            assertTrue(openEntered.await(3, TimeUnit.SECONDS));
            SQLException failure = worker.get(3, TimeUnit.SECONDS);
            assertTrue(failure instanceof SQLTimeoutException);
            assertEquals("08001", failure.getSQLState());
            assertTrue(original.closed.get());
            assertEquals(1, releaseOpen.getCount()); // The worker exited while connection setup is still blocked.
            assertEquals(0, lateConnection.executions.get());
            // A second finally block must not start more connection attempts after the timeout.
            try (DataSyncCancellation.CleanupScope ignored = cancellation.allowCleanup()) {
                try { connection.createStatement(); fail("A timed-out cleanup connection must stay unavailable"); }
                catch (SQLException expected) { assertEquals("08001", expected.getSQLState()); }
            }
            assertEquals(1, attempts.get());
            releaseOpen.countDown();
            assertTrue(lateConnection.closeEntered.await(3, TimeUnit.SECONDS));
            assertEquals(1, lateConnection.closeCalls.get());
            assertEquals(0, lateConnection.executions.get());
            assertEquals(0, lateConnection.commits.get());
        } finally {
            releaseOpen.countDown();
            original.release();
            lateConnection.release();
        }
    }

    private static FutureTask<Void> cleanup(DataSyncCancellation cancellation, Connection connection, Statement original) {
        return async(() -> {
            try (DataSyncCancellation.CleanupScope ignored = cancellation.allowCleanup();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE temporary");
                connection.commit();
            }
            original.close();
            connection.close();
            return null;
        });
    }

    private static void stopped(Action action) throws Exception {
        try {
            action.run();
            fail("A stopped original session must not execute another operation");
        } catch (DataSyncCancellation.SyncStoppedException expected) {
            assertEquals("57014", expected.getSQLState());
        }
    }

    private interface Action { void run() throws Exception; }
    private enum Abort { SYNCHRONOUS, ASYNCHRONOUS, UNSUPPORTED, NO_OP }

    private static final class Driver {
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        final CountDownLatch executionEntered = new CountDownLatch(1);
        final CountDownLatch executionRelease = new CountDownLatch(1);
        final CountDownLatch cancelEntered = new CountDownLatch(1);
        final CountDownLatch cancelRelease = new CountDownLatch(1);
        final CountDownLatch cancelCompleted = new CountDownLatch(1);
        final CountDownLatch abortInvoked = new CountDownLatch(1);
        final CountDownLatch abortTaskEntered = new CountDownLatch(1);
        final CountDownLatch abortTaskRelease = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        volatile boolean blockExecution;
        volatile boolean releaseExecutionOnlyAfterAbort;
        final Connection connection;

        Driver(Abort behavior) {
            connection = proxy(Connection.class, (object, method, args) -> {
                String name = method.getName();
                if ("isClosed".equals(name)) return closed.get();
                if ("abort".equals(name)) {
                    aborts.incrementAndGet();
                    abortInvoked.countDown();
                    if (behavior == Abort.UNSUPPORTED) throw new SQLFeatureNotSupportedException("abort unavailable");
                    if (behavior == Abort.ASYNCHRONOUS) {
                        ((Executor) args[0]).execute(() -> {
                            abortTaskEntered.countDown();
                            try {
                                if (!abortTaskRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Abort test timed out");
                                closed.set(true);
                                executionRelease.countDown();
                            } catch (InterruptedException error) { throw new IllegalStateException(error); }
                        });
                    } else if (behavior == Abort.SYNCHRONOUS) {
                        closed.set(true);
                        executionRelease.countDown();
                    }
                    return null;
                }
                if ("createStatement".equals(name)) return proxy(Statement.class, (statement, operation, values) -> {
                    if ("cancel".equals(operation.getName())) {
                        cancels.incrementAndGet();
                        cancelEntered.countDown();
                        if (!cancelRelease.await(5, TimeUnit.SECONDS)) throw new SQLException("Cancel test timed out");
                        if (releaseExecutionOnlyAfterAbort && abortInvoked.getCount() == 0) executionRelease.countDown();
                        cancelCompleted.countDown();
                    }
                    if (operation.getName().startsWith("execute")) {
                        executions.incrementAndGet();
                        executionEntered.countDown();
                        if (blockExecution && !executionRelease.await(5, TimeUnit.SECONDS)) throw new SQLException("Execute test timed out");
                    }
                    return defaultValue(operation.getReturnType());
                });
                if ("commit".equals(name)) commits.incrementAndGet();
                if ("close".equals(name)) { closeCalls.incrementAndGet(); closed.set(true); closeEntered.countDown(); }
                return defaultValue(method.getReturnType());
            });
        }

        void release() {
            cancelRelease.countDown();
            abortTaskRelease.countDown();
            executionRelease.countDown();
        }
    }

    private static <T> FutureTask<T> async(java.util.concurrent.Callable<T> callable) {
        FutureTask<T> task = new FutureTask<>(callable);
        Thread thread = new Thread(task, "abort-test");
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(DataSyncCancellationAbortTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
