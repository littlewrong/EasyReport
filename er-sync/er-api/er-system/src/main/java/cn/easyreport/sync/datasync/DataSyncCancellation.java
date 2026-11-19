package cn.easyreport.sync.datasync;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns cancellation for one synchronization worker, including its streaming JDBC reads. */
public final class DataSyncCancellation implements AutoCloseable {
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadFactory DAEMON_THREADS = runnable -> {
        Thread thread = new Thread(runnable, "data-sync-cancel-" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    // A driver may block in cancel(); it must not hold up the stop request or other tasks.
    private static final ExecutorService CANCEL_WORKERS = Executors.newCachedThreadPool(DAEMON_THREADS);
    private static final long CANCEL_GRACE_MILLIS = 5_000;
    private static final long CLEANUP_CONNECT_TIMEOUT_MILLIS = 5_000;

    private final Object lock = new Object();
    private final Set<TrackedStatement> statements = Collections.newSetFromMap(new IdentityHashMap<TrackedStatement, Boolean>());
    private final Set<TrackedConnection> connections = Collections.newSetFromMap(new IdentityHashMap<TrackedConnection, Boolean>());
    private final Set<PendingCancel> pendingCancels = Collections.newSetFromMap(new IdentityHashMap<PendingCancel, Boolean>());
    private final long cancelGraceNanos;
    private final long cleanupConnectTimeoutMillis;
    private final ThreadLocal<Integer> cleanupDepth = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };
    private volatile DataSyncExecutor.StopChecker checker;
    private volatile boolean stopRequested;
    private boolean closed;
    private int cleanupScopes;
    private ScheduledExecutorService watcher;
    private long stoppedAtNanos;

    public DataSyncCancellation() {
        this(CANCEL_GRACE_MILLIS);
    }

    DataSyncCancellation(long cancelGraceMillis) {
        this(cancelGraceMillis, CLEANUP_CONNECT_TIMEOUT_MILLIS);
    }

    DataSyncCancellation(long cancelGraceMillis, long cleanupConnectTimeoutMillis) {
        if (cancelGraceMillis < 0) throw new IllegalArgumentException("Cancellation grace must not be negative");
        if (cleanupConnectTimeoutMillis <= 0) throw new IllegalArgumentException("Cleanup connection timeout must be positive");
        cancelGraceNanos = TimeUnit.MILLISECONDS.toNanos(cancelGraceMillis);
        this.cleanupConnectTimeoutMillis = cleanupConnectTimeoutMillis;
    }

    /** A separate session is permitted only for cleanup after the original session is terminated. */
    @FunctionalInterface
    public interface CleanupConnectionFactory {
        Connection open() throws Exception;
    }

    /** Does not reset a stop request received before worker initialization. */
    public void start(DataSyncExecutor.StopChecker stopChecker) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Cancellation context is closed");
            }
            checker = stopChecker;
            ensureWatcherLocked();
        }
        pollStopChecker();
    }

    /** Latches immediately and dispatches JDBC cancellation without blocking the caller. */
    public void requestStop() {
        synchronized (lock) {
            if (!stopRequested) stoppedAtNanos = System.nanoTime();
            stopRequested = true;
            if (!closed) {
                ensureWatcherLocked();
                if (cleanupScopes == 0) cancelStatementsLocked();
                abortOverdueConnectionsLocked();
            }
        }
    }

    public boolean isStopRequested() {
        if (!stopRequested) pollStopChecker();
        return stopRequested;
    }

    public void checkStopped() throws SyncStoppedException {
        if (cleanupDepth.get() == 0 && isStopRequested()) {
            throw new SyncStoppedException();
        }
    }

    private void pollStopChecker() {
        if (stopRequested) {
            // cancel() can race just before the driver starts executing and be a no-op.
            // Retry while the worker still owns this statement, with one call in flight.
            requestStop();
            return;
        }
        DataSyncExecutor.StopChecker current = checker;
        if (current != null) {
            try {
                if (current.shouldStop()) {
                    requestStop();
                }
            } catch (RuntimeException ignored) {
                // A failed status check does not authorize cancelling a running task.
            }
        }
    }

    private void ensureWatcherLocked() {
        if (watcher == null) {
            watcher = Executors.newSingleThreadScheduledExecutor(DAEMON_THREADS);
            watcher.scheduleWithFixedDelay(this::pollStopChecker, 200, 200, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelStatementsLocked() {
        for (TrackedStatement statement : statements) {
            if (statement.statementClosed || (statement.connection.abortRequested && !statement.connection.abortFailed)
                    || (statement.cancelCompletion != null && statement.cancelCompletion.completed.getCount() != 0)) {
                continue;
            }
            PendingCancel completed = new PendingCancel(statement.connection);
            statement.cancelCompletion = completed;
            pendingCancels.add(completed);
            CANCEL_WORKERS.execute(() -> {
                try {
                    synchronized (lock) {
                        if (closed || statement.statementClosed) {
                            return;
                        }
                    }
                    statement.raw.cancel();
                } catch (SQLException | RuntimeException ignored) {
                    // Loop checks still stop the worker if a driver cannot cancel its query.
                } finally {
                    synchronized (lock) {
                        pendingCancels.remove(completed);
                    }
                    completed.completed.countDown();
                }
            });
        }
    }

    /** Escalate only this run's original sessions; never release the run merely because time elapsed. */
    private void abortOverdueConnectionsLocked() {
        if (System.nanoTime() - stoppedAtNanos < cancelGraceNanos) return;
        for (TrackedConnection connection : connections) {
            if (connection.connectionClosed || connection.abortRequested) continue;
            boolean occupied = connection.closeStarted;
            for (TrackedStatement statement : statements) {
                if (statement.connection == connection && !statement.statementClosed && cleanupScopes == 0) occupied = true;
            }
            for (PendingCancel pending : pendingCancels) {
                if (pending.connection == connection && pending.completed.getCount() != 0) occupied = true;
            }
            if (!occupied) continue;
            connection.abortRequested = true;
            CANCEL_WORKERS.execute(() -> abortConnection(connection));
        }
    }

    private void abortConnection(TrackedConnection connection) {
        // JDBC drivers may delegate the actual disconnect to the supplied Executor.
        // The method returning alone does not establish that the session was closed.
        AtomicInteger outstanding = new AtomicInteger(1);
        Runnable completed = () -> {
            if (outstanding.decrementAndGet() != 0) return;
            try {
                if (!connection.abortFailed && connection.raw.isClosed()) connection.aborted = true;
                else connection.abortFailed = true;
            } catch (SQLException | RuntimeException ignored) {
                // An unconfirmed disconnect cannot authorize cleanup reuse or a stopped state.
                connection.abortFailed = true;
            } finally {
                connection.abortCompleted.countDown();
            }
        };
        try {
            connection.raw.abort(command -> {
                outstanding.incrementAndGet();
                CANCEL_WORKERS.execute(() -> {
                    try { command.run(); }
                    catch (RuntimeException error) { connection.abortFailed = true; }
                    finally { completed.run(); }
                });
            });
        } catch (SQLException | RuntimeException error) {
            connection.abortFailed = true;
        } finally {
            completed.run();
        }
    }

    public Connection wrap(Connection raw) {
        return wrap(raw, null);
    }

    public Connection wrap(Connection raw, CleanupConnectionFactory cleanupFactory) {
        if (raw == null) {
            return null;
        }
        TrackedConnection trackedConnection = new TrackedConnection(raw, cleanupFactory);
        synchronized (lock) { connections.add(trackedConnection); }
        Connection[] wrapped = new Connection[1];
        wrapped[0] = proxy(Connection.class, (object, method, args) -> {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(object, method, args);
            }
            if ("unwrap".equals(name) && ((Class<?>) args[0]).isInstance(object)) {
                return object;
            }
            if ("isWrapperFor".equals(name) && ((Class<?>) args[0]).isInstance(object)) {
                return true;
            }
            boolean create = "createStatement".equals(name) || "prepareStatement".equals(name) || "prepareCall".equals(name);
            if (create || "commit".equals(name) || "setAutoCommit".equals(name) || "getMetaData".equals(name)) {
                checkStopped();
            }
            if ("close".equals(name)) {
                List<TrackedStatement> connectionStatements = new ArrayList<>();
                synchronized (lock) {
                    trackedConnection.closeStarted = true;
                    for (TrackedStatement tracked : statements) {
                        if (tracked.connection == trackedConnection) connectionStatements.add(tracked);
                    }
                }
                for (TrackedStatement tracked : connectionStatements) awaitCancellation(tracked);
                try {
                    if (trackedConnection.aborted) {
                        if (trackedConnection.cleanup != null) trackedConnection.cleanup.close();
                        return null;
                    }
                    return invoke(raw, method, args);
                } finally {
                    synchronized (lock) {
                        trackedConnection.connectionClosed = true;
                        for (TrackedStatement tracked : connectionStatements) {
                            tracked.statementClosed = true;
                            statements.remove(tracked);
                        }
                    }
                }
            }
            if (trackedConnection.aborted && "isClosed".equals(name)) return true;
            if (trackedConnection.aborted && "rollback".equals(name) && cleanupDepth.get() == 0) return null;
            // Even if cancel just returned, an abort dispatched earlier may still be
            // disconnecting the original session. Do not start cleanup on it meanwhile.
            if (cleanupDepth.get() > 0 && trackedConnection.abortRequested) awaitAbort(trackedConnection);
            Connection delegate = trackedConnection.aborted ? cleanupConnection(trackedConnection) : raw;
            Object result = invoke(delegate, method, args);
            if (result instanceof Statement) {
                return wrapStatement((Statement) result, trackedConnection, wrapped[0]);
            }
            if (result instanceof DatabaseMetaData) {
                return wrapMetadata((DatabaseMetaData) result, trackedConnection, wrapped[0]);
            }
            return result;
        });
        return wrapped[0];
    }

    private static void awaitAbort(TrackedConnection connection) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    connection.abortCompleted.await();
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private Connection cleanupConnection(TrackedConnection connection) throws SQLException {
        if (cleanupDepth.get() == 0) throw new SyncStoppedException();
        if (connection.cleanupFactory == null) throw new SQLException("同步连接已中止，无法复用原连接执行清理", "08003");
        synchronized (connection) {
            if (connection.cleanup == null) {
                if (connection.cleanupUnavailable) throw new SQLException("同步清理连接建立失败或已超时", "08001");
                connection.cleanupUnavailable = true;
                connection.cleanup = openCleanupConnection(connection.cleanupFactory);
                if (connection.cleanup == connection.raw) {
                    connection.cleanup = null;
                    throw new SQLException("清理连接未与已中止连接隔离", "08003");
                }
            }
            return connection.cleanup;
        }
    }

    private Connection openCleanupConnection(CleanupConnectionFactory factory) throws SQLException {
        CleanupAttempt attempt = new CleanupAttempt();
        CANCEL_WORKERS.execute(() -> {
            Connection opened = null;
            Exception failure = null;
            try { opened = factory.open(); }
            catch (Exception error) { failure = error; }
            boolean abandoned;
            synchronized (attempt) {
                abandoned = attempt.abandoned;
                if (!abandoned) {
                    attempt.connection = opened;
                    attempt.failure = failure;
                }
                attempt.completed.countDown();
            }
            // A timeout never hands this session to cleanup SQL. If the driver only
            // returns later, dispose it on this daemon thread without delaying the run.
            if (abandoned) closeAbandonedConnection(opened);
        });
        try {
            if (!attempt.completed.await(cleanupConnectTimeoutMillis, TimeUnit.MILLISECONDS)) {
                abandonCleanupAttempt(attempt);
                throw new SQLTimeoutException("重新建立同步清理连接超时，请检查并清理残留临时外表", "08001");
            }
        } catch (InterruptedException interrupted) {
            abandonCleanupAttempt(attempt);
            Thread.currentThread().interrupt();
            throw new SQLException("重新建立同步清理连接被中断", "57014");
        }
        synchronized (attempt) {
            if (attempt.failure instanceof SQLException) throw (SQLException) attempt.failure;
            if (attempt.failure != null || attempt.connection == null) throw new SQLException("重新建立同步清理连接失败", "08001");
            Connection opened = attempt.connection;
            attempt.connection = null;
            return opened;
        }
    }

    private static void abandonCleanupAttempt(CleanupAttempt attempt) {
        Connection opened;
        synchronized (attempt) {
            attempt.abandoned = true;
            opened = attempt.connection;
            attempt.connection = null;
        }
        if (opened != null) CANCEL_WORKERS.execute(() -> closeAbandonedConnection(opened));
    }

    private static void closeAbandonedConnection(Connection connection) {
        if (connection == null) return;
        try { connection.close(); }
        catch (SQLException | RuntimeException ignored) { }
    }

    private Statement wrapStatement(Statement raw, TrackedConnection trackedConnection, Connection connection) throws SQLException {
        TrackedStatement tracked = new TrackedStatement(raw, trackedConnection);
        final boolean cleanupStatement = cleanupDepth.get() > 0;
        boolean reject;
        synchronized (lock) {
            reject = cleanupDepth.get() == 0 && (stopRequested || closed);
            if (!reject && cleanupDepth.get() == 0) {
                statements.add(tracked);
            }
        }
        if (reject) {
            try { raw.close(); } catch (SQLException ignored) { }
            throw new SyncStoppedException();
        }
        Class<? extends Statement> type = raw instanceof CallableStatement ? CallableStatement.class
                : raw instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        tracked.wrapped = proxy(type, (object, method, args) -> {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(object, method, args);
            }
            if ("getConnection".equals(name)) {
                return connection;
            }
            if ("unwrap".equals(name) && ((Class<?>) args[0]).isInstance(object)) {
                return object;
            }
            if ("isWrapperFor".equals(name) && ((Class<?>) args[0]).isInstance(object)) {
                return true;
            }
            if (name.startsWith("execute") || "addBatch".equals(name)) {
                checkStopped();
            }
            if ("close".equals(name)) {
                awaitCancellation(tracked);
                try {
                    if (trackedConnection.aborted && !cleanupStatement) return null;
                    return invoke(raw, method, args);
                } finally {
                    synchronized (lock) {
                        tracked.statementClosed = true;
                        statements.remove(tracked);
                    }
                }
            }
            if (trackedConnection.aborted && !cleanupStatement) throw new SyncStoppedException();
            Object result = invoke(raw, method, args);
            return result instanceof ResultSet ? wrapResultSet((ResultSet) result, tracked, trackedConnection, cleanupStatement) : result;
        });
        return tracked.wrapped;
    }

    private DatabaseMetaData wrapMetadata(DatabaseMetaData raw, TrackedConnection trackedConnection, Connection connection) {
        final boolean cleanupMetadata = cleanupDepth.get() > 0;
        return proxy(DatabaseMetaData.class, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(object, method, args);
            }
            if ("getConnection".equals(method.getName())) {
                return connection;
            }
            if (trackedConnection.aborted && !cleanupMetadata) throw new SyncStoppedException();
            checkStopped();
            Object result = invoke(raw, method, args);
            return result instanceof ResultSet ? wrapResultSet((ResultSet) result, null, trackedConnection, cleanupMetadata) : result;
        });
    }

    private ResultSet wrapResultSet(ResultSet raw, TrackedStatement statement, TrackedConnection connection, boolean cleanupResult) {
        return proxy(ResultSet.class, (object, method, args) -> {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(object, method, args);
            }
            if ("getStatement".equals(name) && statement != null) {
                return statement.wrapped;
            }
            if ("next".equals(name)) {
                checkStopped();
            }
            if ("close".equals(name) && statement != null) {
                awaitCancellation(statement);
            }
            if ("close".equals(name) && connection.aborted && !cleanupResult) return null;
            if (connection.aborted && !cleanupResult) throw new SyncStoppedException();
            return invoke(raw, method, args);
        });
    }

    /** A streaming close can drain the server response; cancel must reach the driver first. */
    private void awaitCancellation(TrackedStatement statement) {
        PendingCancel completed;
        synchronized (lock) {
            completed = statement.cancelCompletion;
        }
        if (completed == null) return;
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (completed.connection.aborted || completed.completed.await(100, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Lets finally-block SQL remove temporary resources after a stop. Previously issued
     * cancel calls finish first, so a late cancel cannot hit cleanup on that connection.
     * A scope must be opened and closed on the same worker thread.
     */
    public CleanupScope allowCleanup() throws InterruptedException {
        List<PendingCancel> outstanding;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Cancellation context is closed");
            }
            cleanupScopes++;
            cleanupDepth.set(cleanupDepth.get() + 1);
            outstanding = new ArrayList<>(pendingCancels);
        }
        CleanupScope scope = new CleanupScope();
        try {
            for (PendingCancel completed : outstanding) {
                while (!completed.connection.aborted && !completed.completed.await(100, TimeUnit.MILLISECONDS)) { }
            }
            return scope;
        } catch (InterruptedException error) {
            scope.close();
            Thread.currentThread().interrupt();
            throw error;
        }
    }

    public final class CleanupScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private boolean scopeClosed;

        private CleanupScope() { }

        @Override public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Cleanup scope belongs to another worker");
            }
            synchronized (lock) {
                if (scopeClosed) {
                    return;
                }
                scopeClosed = true;
                int depth = cleanupDepth.get() - 1;
                if (depth == 0) {
                    cleanupDepth.remove();
                } else {
                    cleanupDepth.set(depth);
                }
                cleanupScopes--;
                if (stopRequested && !closed && cleanupScopes == 0) {
                    cancelStatementsLocked();
                }
            }
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            checker = null;
            if (watcher != null) {
                watcher.shutdownNow();
            }
            statements.clear();
            connections.clear();
        }
    }

    public static final class SyncStoppedException extends SQLException {
        public SyncStoppedException() {
            super("Synchronization task was stopped", "57014");
        }
    }

    private static final class TrackedStatement {
        private final Statement raw;
        private final TrackedConnection connection;
        private Statement wrapped;
        private boolean statementClosed;
        private PendingCancel cancelCompletion;

        private TrackedStatement(Statement raw, TrackedConnection connection) {
            this.raw = raw;
            this.connection = connection;
        }
    }

    private static final class TrackedConnection {
        final Connection raw;
        final CleanupConnectionFactory cleanupFactory;
        volatile Connection cleanup;
        boolean cleanupUnavailable;
        boolean connectionClosed;
        boolean closeStarted;
        volatile boolean abortRequested;
        final CountDownLatch abortCompleted = new CountDownLatch(1);
        volatile boolean abortFailed;
        volatile boolean aborted;

        TrackedConnection(Connection raw, CleanupConnectionFactory cleanupFactory) {
            this.raw = raw;
            this.cleanupFactory = cleanupFactory;
        }
    }

    private static final class PendingCancel {
        final TrackedConnection connection;
        final CountDownLatch completed = new CountDownLatch(1);

        PendingCancel(TrackedConnection connection) { this.connection = connection; }
    }

    private static final class CleanupAttempt {
        final CountDownLatch completed = new CountDownLatch(1);
        Connection connection;
        Exception failure;
        boolean abandoned;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(DataSyncCancellation.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object invoke(Object raw, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(raw, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static Object objectMethod(Object object, Method method, Object[] args) {
        if ("equals".equals(method.getName())) {
            return object == args[0];
        }
        if ("hashCode".equals(method.getName())) {
            return System.identityHashCode(object);
        }
        return "DataSyncJDBC[" + object.getClass().getInterfaces()[0].getSimpleName() + "]";
    }
}
