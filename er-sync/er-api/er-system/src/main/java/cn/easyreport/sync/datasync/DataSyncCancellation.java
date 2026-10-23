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

    private final Object lock = new Object();
    private final Set<TrackedStatement> statements = Collections.newSetFromMap(new IdentityHashMap<TrackedStatement, Boolean>());
    private final Set<CountDownLatch> pendingCancels = Collections.newSetFromMap(new IdentityHashMap<CountDownLatch, Boolean>());
    private final ThreadLocal<Integer> cleanupDepth = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };
    private volatile DataSyncExecutor.StopChecker checker;
    private volatile boolean stopRequested;
    private boolean closed;
    private int cleanupScopes;
    private ScheduledExecutorService watcher;

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
            stopRequested = true;
            if (!closed) {
                ensureWatcherLocked();
                if (cleanupScopes == 0) cancelStatementsLocked();
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
            if (statement.statementClosed || (statement.cancelCompletion != null
                    && statement.cancelCompletion.getCount() != 0)) {
                continue;
            }
            CountDownLatch completed = new CountDownLatch(1);
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
                    completed.countDown();
                }
            });
        }
    }

    public Connection wrap(Connection raw) {
        if (raw == null) {
            return null;
        }
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
                    for (TrackedStatement tracked : statements) {
                        if (tracked.connection == wrapped[0]) connectionStatements.add(tracked);
                    }
                }
                for (TrackedStatement tracked : connectionStatements) awaitCancellation(tracked);
                try {
                    return invoke(raw, method, args);
                } finally {
                    synchronized (lock) {
                        for (TrackedStatement tracked : connectionStatements) {
                            tracked.statementClosed = true;
                            statements.remove(tracked);
                        }
                    }
                }
            }
            Object result = invoke(raw, method, args);
            if (result instanceof Statement) {
                return wrapStatement((Statement) result, wrapped[0]);
            }
            if (result instanceof DatabaseMetaData) {
                return wrapMetadata((DatabaseMetaData) result, wrapped[0]);
            }
            return result;
        });
        return wrapped[0];
    }

    private Statement wrapStatement(Statement raw, Connection connection) throws SQLException {
        TrackedStatement tracked = new TrackedStatement(raw, connection);
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
                    return invoke(raw, method, args);
                } finally {
                    synchronized (lock) {
                        tracked.statementClosed = true;
                        statements.remove(tracked);
                    }
                }
            }
            Object result = invoke(raw, method, args);
            return result instanceof ResultSet ? wrapResultSet((ResultSet) result, tracked) : result;
        });
        return tracked.wrapped;
    }

    private DatabaseMetaData wrapMetadata(DatabaseMetaData raw, Connection connection) {
        return proxy(DatabaseMetaData.class, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(object, method, args);
            }
            if ("getConnection".equals(method.getName())) {
                return connection;
            }
            checkStopped();
            Object result = invoke(raw, method, args);
            return result instanceof ResultSet ? wrapResultSet((ResultSet) result, null) : result;
        });
    }

    private ResultSet wrapResultSet(ResultSet raw, TrackedStatement statement) {
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
            return invoke(raw, method, args);
        });
    }

    /** A streaming close can drain the server response; cancel must reach the driver first. */
    private void awaitCancellation(TrackedStatement statement) {
        CountDownLatch completed;
        synchronized (lock) {
            completed = statement.cancelCompletion;
        }
        if (completed == null) return;
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    completed.await();
                    return;
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
        List<CountDownLatch> outstanding;
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
            for (CountDownLatch completed : outstanding) {
                completed.await();
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
        }
    }

    public static final class SyncStoppedException extends SQLException {
        public SyncStoppedException() {
            super("Synchronization task was stopped", "57014");
        }
    }

    private static final class TrackedStatement {
        private final Statement raw;
        private final Connection connection;
        private Statement wrapped;
        private boolean statementClosed;
        private CountDownLatch cancelCompletion;

        private TrackedStatement(Statement raw, Connection connection) {
            this.raw = raw;
            this.connection = connection;
        }
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
