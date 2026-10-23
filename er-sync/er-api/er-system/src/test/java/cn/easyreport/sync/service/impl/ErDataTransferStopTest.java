package cn.easyreport.sync.service.impl;

import cn.easyreport.sync.core.SyncProgressCallback;
import cn.easyreport.sync.datasync.DataSyncExecutor;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

import static org.junit.Assert.*;

/** No Spring context or database is started; latches model a worker still cleaning resources. */
public class ErDataTransferStopTest
{
    private static final AtomicLong IDS = new AtomicLong(80000);

    @Test
    public void stopCancelsActiveExecutorButRetainsLockUntilCleanupCompletes() throws Exception
    {
        Fixture fixture = new Fixture();
        try {
            fixture.start();
            assertTrue(fixture.service.stopTransfer(fixture.id).get("success").equals(true));
            assertEquals("5", fixture.task.getSyncStatus());
            assertTrue(fixture.task.getSyncProgress().contains("正在停止"));
            assertTrue(fixture.executor.isStopRequested());
            assertTrue(map("activeExecutors").containsKey(fixture.id));
            assertTrue(map("runningTransfers").containsKey(fixture.id));
            assertEquals(false, fixture.service.executeTransfer(fixture.id).get("success"));

            // A last progress callback after cancellation must not replace the stopping message.
            fixture.executor.progress.onProgress(100, 200, "继续同步");
            assertTrue(fixture.task.getSyncProgress().contains("正在停止"));
            assertEquals(true, fixture.service.stopTransfer(fixture.id).get("success"));
            assertEquals("5", fixture.task.getSyncStatus());
        } finally {
            fixture.close();
        }
        assertEquals("4", fixture.task.getSyncStatus());
        assertFalse(map("activeExecutors").containsKey(fixture.id));
        assertFalse(map("stopFlags").containsKey(fixture.id));
    }

    @Test
    public void requestBeforeExecutorIsRegisteredIsForwardedAfterCreation() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.blockCreation = true;
        try {
            assertEquals(true, fixture.service.executeTransfer(fixture.id).get("success"));
            assertTrue(fixture.creationEntered.await(3, TimeUnit.SECONDS));
            assertEquals(true, fixture.service.stopTransfer(fixture.id).get("success"));
            assertFalse(map("activeExecutors").containsKey(fixture.id));
            fixture.allowCreation.countDown();
            assertTrue(fixture.executor.entered.await(3, TimeUnit.SECONDS));
            assertTrue(fixture.executor.isStopRequested());
            assertEquals("5", fixture.task.getSyncStatus());
        } finally {
            fixture.close();
        }
        assertEquals("4", fixture.task.getSyncStatus());
    }

    @Test
    public void cancellationExceptionDoesNotReplaceStopWithFailure() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.executor.failAfterRelease = true;
        try {
            fixture.start();
            fixture.service.stopTransfer(fixture.id);
        } finally {
            fixture.close();
        }
        assertEquals("4", fixture.task.getSyncStatus());
    }

    @Test
    public void legacyStoppedStatusWithLiveWorkerCanStillBeCancelled() throws Exception
    {
        Fixture fixture = new Fixture();
        try {
            fixture.start();
            fixture.task.setSyncStatus("4");
            assertEquals(false, fixture.service.executeTransfer(fixture.id).get("success"));
            assertEquals(true, fixture.service.stopTransfer(fixture.id).get("success"));
            assertEquals("5", fixture.task.getSyncStatus());
            assertTrue(fixture.executor.isStopRequested());
        } finally {
            fixture.close();
        }
        assertEquals("4", fixture.task.getSyncStatus());
    }

    @Test
    public void remoteWorkerReceivesPersistedStopWithoutFalseStoppedState() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.task.setSyncStatus("1");
        assertEquals(true, fixture.service.stopTransfer(fixture.id).get("success"));
        assertEquals("5", fixture.task.getSyncStatus());
        assertFalse(map("stopFlags").containsKey(fixture.id));
        assertEquals(true, fixture.service.stopTransfer(fixture.id).get("success"));
        assertEquals(false, fixture.service.executeTransfer(fixture.id).get("success"));
    }

    @Test
    public void losingCrossInstanceClaimDoesNotStartWorkerOrFinishTheOwnersRun() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.competingClaimStatus = "1";
        assertEquals(false, fixture.service.executeTransfer(fixture.id).get("success"));
        assertEquals("1", fixture.task.getSyncStatus());
        assertEquals(0, fixture.finishWrites);
        assertEquals(1, fixture.executor.entered.getCount());
        assertFalse(map("runningTransfers").containsKey(fixture.id));
    }

    @Test
    public void losingClaimToAnotherInstancesStoppingRunDoesNotMarkItStopped() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.competingClaimStatus = "5";
        assertEquals(false, fixture.service.executeTransfer(fixture.id).get("success"));
        assertEquals("5", fixture.task.getSyncStatus());
        assertEquals(0, fixture.finishWrites);
        assertEquals(1, fixture.executor.entered.getCount());
        assertFalse(map("runningTransfers").containsKey(fixture.id));
    }

    @Test
    public void datasourceFailureBeforeClaimDoesNotFinishAnotherInstancesRun() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.failDatasourceLookup = true;
        assertEquals(false, fixture.service.executeTransfer(fixture.id).get("success"));
        assertEquals("1", fixture.task.getSyncStatus());
        assertEquals(0, fixture.finishWrites);
        assertEquals(0, fixture.claimAttempts);
        assertEquals(1, fixture.executor.entered.getCount());
        assertFalse(map("runningTransfers").containsKey(fixture.id));
    }

    @Test
    public void workerObservesAnotherInstancesPersistedRequest() throws Exception
    {
        Fixture fixture = new Fixture();
        try {
            fixture.start();
            fixture.task.setSyncStatus("5");
            fixture.executor.pollDatabaseStop = true;
            assertTrue("worker must observe persisted stop", fixture.executor.observedStop.await(3, TimeUnit.SECONDS));
        } finally {
            fixture.close();
        }
        assertEquals("4", fixture.task.getSyncStatus());
    }

    @Test
    public void lateRemoteRequestWinsAgainstSuccessfulCompletion() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.remoteStopAtFinish = true;
        try {
            fixture.start();
        } finally {
            fixture.close();
        }
        assertEquals("4", fixture.task.getSyncStatus());
    }

    @Test
    public void completedWorkerCannotBeChangedBackToStopping() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.close();
        assertEquals("2", fixture.task.getSyncStatus());
        assertEquals(false, fixture.service.stopTransfer(fixture.id).get("success"));
        assertEquals("2", fixture.task.getSyncStatus());
    }

    @Test
    public void ordinaryFailureStillFinishesAsFailed() throws Exception
    {
        Fixture fixture = new Fixture();
        fixture.executor.failAfterRelease = true;
        fixture.start();
        fixture.close();
        assertEquals("3", fixture.task.getSyncStatus());
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, Object> map(String name) throws Exception
    {
        Field field = ErDataTransferServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ConcurrentHashMap<Long, Object>) field.get(null);
    }

    private static class Fixture
    {
        final long id = IDS.incrementAndGet();
        final ErDataTransfer task = new ErDataTransfer();
        final BlockingExecutor executor = new BlockingExecutor();
        final CountDownLatch creationEntered = new CountDownLatch(1);
        final CountDownLatch allowCreation = new CountDownLatch(1);
        volatile boolean blockCreation;
        volatile boolean remoteStopAtFinish;
        volatile String competingClaimStatus;
        volatile boolean failDatasourceLookup;
        volatile int finishWrites;
        volatile int claimAttempts;
        final ErDataTransferServiceImpl service = new ErDataTransferServiceImpl() {
            @Override
            protected DataSyncExecutor createExecutor(ErDatasource target) {
                creationEntered.countDown();
                if (blockCreation) {
                    try {
                        if (!allowCreation.await(3, TimeUnit.SECONDS)) throw new AssertionError("creation not released");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return executor;
            }
        };

        Fixture() throws Exception
        {
            task.setTransferId(id);
            task.setSyncStatus("0");
            task.setSourceDatasourceId(1L);
            task.setTargetDatasourceId(2L);
            inject("erDataTransferMapper", Proxy.newProxyInstance(ErDataTransferMapper.class.getClassLoader(),
                    new Class<?>[] {ErDataTransferMapper.class}, (proxy, method, args) -> {
                        synchronized (task) {
                            switch (method.getName()) {
                                case "selectErDataTransferByTransferId": return task;
                                case "updateErDataTransfer":
                                    ErDataTransfer update = (ErDataTransfer) args[0];
                                    if (update.getSyncStatus() != null) task.setSyncStatus(update.getSyncStatus());
                                    if (update.getSyncProgress() != null) task.setSyncProgress(update.getSyncProgress());
                                    return 1;
                                case "claimTransfer":
                                    claimAttempts++;
                                    if (competingClaimStatus != null) task.setSyncStatus(competingClaimStatus);
                                    if ("1".equals(task.getSyncStatus()) || "5".equals(task.getSyncStatus())) return 0;
                                    task.setSyncStatus("1");
                                    task.setSyncProgress(((ErDataTransfer) args[0]).getSyncProgress());
                                    return 1;
                                case "requestTransferStop":
                                    if ("1".equals(task.getSyncStatus()) || "5".equals(task.getSyncStatus())
                                            || Boolean.TRUE.equals(args[2])) {
                                        task.setSyncStatus("5");
                                        task.setSyncProgress((String) args[1]);
                                        return 1;
                                    }
                                    return 0;
                                case "updateTransferProgress":
                                    if (!"1".equals(task.getSyncStatus())) return 0;
                                    task.setSyncProgress(((ErDataTransfer) args[0]).getSyncProgress());
                                    return 1;
                                case "finishTransfer":
                                    finishWrites++;
                                    if (remoteStopAtFinish) task.setSyncStatus("5");
                                    ErDataTransfer finish = (ErDataTransfer) args[0];
                                    task.setSyncStatus("5".equals(task.getSyncStatus()) || "4".equals(task.getSyncStatus())
                                            ? "4" : finish.getSyncStatus());
                                    task.setLastSyncResult(finish.getLastSyncResult());
                                    return 1;
                                default: throw new AssertionError("Unexpected mapper method: " + method.getName());
                            }
                        }
                    }));
            inject("erDatasourceMapper", Proxy.newProxyInstance(ErDatasourceMapper.class.getClassLoader(),
                    new Class<?>[] {ErDatasourceMapper.class}, (proxy, method, args) -> {
                        if (failDatasourceLookup) {
                            task.setSyncStatus("1"); // Another instance claimed after the initial task read.
                            throw new IllegalStateException("datasource unavailable before claim");
                        }
                        ErDatasource datasource = new ErDatasource();
                        datasource.setDatasourceType("MYSQL");
                        return datasource;
                    }));
        }

        void start() throws Exception
        {
            assertEquals(true, service.executeTransfer(id).get("success"));
            assertTrue(executor.entered.await(3, TimeUnit.SECONDS));
        }

        void close() throws Exception
        {
            allowCreation.countDown();
            executor.release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (map("runningTransfers").containsKey(id) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse("worker should release its run lock after cleanup", map("runningTransfers").containsKey(id));
        }

        private void inject(String name, Object value) throws Exception
        {
            Field field = ErDataTransferServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        }
    }

    private static class BlockingExecutor extends DataSyncExecutor
    {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch observedStop = new CountDownLatch(1);
        volatile SyncProgressCallback progress;
        volatile boolean failAfterRelease;
        volatile boolean pollDatabaseStop;

        BlockingExecutor() { super(null, null, null); }

        @Override
        protected void clearTable(Connection conn, String tableName) { }

        @Override
        public DataSyncResult execute(ErDataTransfer task, ErDatasource source, ErDatasource target,
                                      SyncProgressCallback callback, StopChecker checker) throws Exception
        {
            progress = callback;
            entered.countDown();
            while (!release.await(10, TimeUnit.MILLISECONDS)) {
                if (pollDatabaseStop && checker.shouldStop()) observedStop.countDown();
            }
            if (failAfterRelease) throw new SQLException("query cancelled or ordinary SQL failure");
            // Return success intentionally: a last-moment stop request must still win.
            DataSyncResult result = new DataSyncResult();
            result.setSuccess(true);
            result.setMessage("completed");
            return result;
        }
    }
}
