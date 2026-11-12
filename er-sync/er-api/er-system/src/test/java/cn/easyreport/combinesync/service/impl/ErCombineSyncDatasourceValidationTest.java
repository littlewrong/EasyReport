package cn.easyreport.combinesync.service.impl;

import cn.easyreport.common.exception.ServiceException;
import cn.easyreport.combinesync.domain.ErCombineSync;
import cn.easyreport.combinesync.mapper.ErCombineSyncMapper;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

public class ErCombineSyncDatasourceValidationTest
{
    private static final Long[][] INVALID_DATASOURCES = {
        {null, null}, {1L, null}, {null, 1L}, {1L, 2L}
    };

    @Test
    public void sameDatasourceCanMergeAcrossDifferentSchemas() throws Exception
    {
        Fixture fixture = new Fixture(1024L, 1024L);
        ErCombineSync task = task(1024L, 1024L);
        task.setCombineId(null);
        task.setSourceSchemaPattern("tenant_%");
        task.setTargetTable("analytics");

        assertEquals(1, fixture.service.insertErCombineSync(task));

        assertSame(task, fixture.saved);
        assertEquals("tenant_%", fixture.saved.getSourceSchemaPattern());
        assertEquals("analytics", fixture.saved.getTargetTable());
        assertEquals(0, fixture.datasourceReads);
    }

    @Test
    public void insertRejectsMissingOrDifferentDatasourcesBeforeSaving() throws Exception
    {
        for (Long[] ids : INVALID_DATASOURCES)
        {
            Fixture fixture = new Fixture(1L, 1L);
            expectSaveRejected(fixture, task(ids[0], ids[1]), false);
        }
    }

    @Test
    public void fullUpdateCanMoveBothSidesToTheSameDatasource() throws Exception
    {
        Fixture fixture = new Fixture(1L, 1L);
        ErCombineSync update = task(1024L, 1024L);
        update.setSourceSchemaPattern("tenant_%");
        update.setTargetTable("analytics");

        assertEquals(1, fixture.service.updateErCombineSync(update));

        assertSame(update, fixture.saved);
        assertEquals(Long.valueOf(1024L), fixture.saved.getSourceDatasourceId());
        assertEquals(Long.valueOf(1024L), fixture.saved.getTargetDatasourceId());
        assertEquals("tenant_%", fixture.saved.getSourceSchemaPattern());
        assertEquals("analytics", fixture.saved.getTargetTable());
    }

    @Test
    public void fullUpdateRejectsDifferentDatasources() throws Exception
    {
        Fixture fixture = new Fixture(1L, 1L);
        expectSaveRejected(fixture, task(1L, 2L), true);
    }

    @Test
    public void oneSidedPatchCannotCreateDifferentDatasources() throws Exception
    {
        for (boolean changeSource : new boolean[] {false, true})
        {
            Fixture fixture = new Fixture(1L, 1L);
            ErCombineSync patch = task(null, null);
            if (changeSource) patch.setSourceDatasourceId(2L);
            else patch.setTargetDatasourceId(2L);

            expectSaveRejected(fixture, patch, true);
        }
    }

    @Test
    public void statusPatchValidatesSavedIdsWithoutWritingThemBack() throws Exception
    {
        Fixture fixture = new Fixture(1L, 1L);
        ErCombineSync patch = task(null, null);
        patch.setSyncStatus("4");

        assertEquals(1, fixture.service.updateErCombineSync(patch));

        assertSame(patch, fixture.saved);
        assertNull(fixture.saved.getSourceDatasourceId());
        assertNull(fixture.saved.getTargetDatasourceId());
        assertEquals("4", fixture.saved.getSyncStatus());
        assertEquals(Long.valueOf(1L), fixture.oldTask.getSourceDatasourceId());
        assertEquals(Long.valueOf(1L), fixture.oldTask.getTargetDatasourceId());
    }

    @Test
    public void legacyInvalidConfigurationMustBeRepairedWhenEditing() throws Exception
    {
        for (Long[] ids : INVALID_DATASOURCES)
        {
            Fixture fixture = new Fixture(ids[0], ids[1]);
            ErCombineSync patch = task(null, null);
            patch.setCombineName("renamed");

            expectSaveRejected(fixture, patch, true);
        }
    }

    @Test
    public void oneSidedPatchCanRepairLegacyDifferentDatasources() throws Exception
    {
        for (boolean changeSource : new boolean[] {false, true})
        {
            Fixture fixture = new Fixture(1L, 2L);
            ErCombineSync patch = task(null, null);
            if (changeSource) patch.setSourceDatasourceId(2L);
            else patch.setTargetDatasourceId(1L);

            assertEquals(1, fixture.service.updateErCombineSync(patch));

            assertSame(patch, fixture.saved);
            if (changeSource) assertNull(fixture.saved.getTargetDatasourceId());
            else assertNull(fixture.saved.getSourceDatasourceId());
        }
    }

    @Test
    public void executeRejectsInvalidSavedTasksBeforeStartingOrChangingStatus() throws Exception
    {
        for (Long[] ids : INVALID_DATASOURCES)
        {
            Fixture fixture = new Fixture(ids[0], ids[1]);

            assertRejectedResult(fixture.service.executeCombineSync(7L));
            // Rejection must not leave the task marked as running.
            assertRejectedResult(fixture.service.executeCombineSync(7L));

            fixture.assertNoSideEffects();
            assertEquals("0", fixture.oldTask.getSyncStatus());
        }
    }

    @Test
    public void previewRejectsInvalidSavedTasksBeforeConnecting() throws Exception
    {
        for (Long[] ids : INVALID_DATASOURCES)
        {
            Fixture fixture = new Fixture(ids[0], ids[1]);
            try
            {
                fixture.service.previewCombineSync(7L);
                fail("Expected invalid datasource configuration to be rejected");
            }
            catch (ServiceException expected)
            {
                assertDatasourceMessage(expected.getMessage());
            }
            fixture.assertNoSideEffects();
        }
    }

    @Test
    public void refreshRejectsInvalidSavedTasksBeforeConnectingOrWritingWatermarks() throws Exception
    {
        for (Long[] ids : INVALID_DATASOURCES)
        {
            Fixture fixture = new Fixture(ids[0], ids[1]);

            assertRejectedResult(fixture.service.refreshProgressFromTarget(7L));

            fixture.assertNoSideEffects();
        }
    }

    private static void expectSaveRejected(Fixture fixture, ErCombineSync task, boolean update)
    {
        try
        {
            if (update) fixture.service.updateErCombineSync(task);
            else fixture.service.insertErCombineSync(task);
            fail("Expected invalid datasource configuration to be rejected");
        }
        catch (ServiceException expected)
        {
            assertDatasourceMessage(expected.getMessage());
        }
        fixture.assertNoSideEffects();
    }

    private static void assertRejectedResult(Map<String, Object> result)
    {
        assertEquals(Boolean.FALSE, result.get("success"));
        assertDatasourceMessage((String) result.get("message"));
    }

    private static void assertDatasourceMessage(String message)
    {
        assertNotNull(message);
        assertTrue(message, message.contains("数据源"));
        assertTrue(message, message.contains("一致") || message.contains("同一个"));
    }

    private static ErCombineSync task(Long sourceId, Long targetId)
    {
        ErCombineSync task = new ErCombineSync();
        task.setCombineId(7L);
        task.setSourceDatasourceId(sourceId);
        task.setTargetDatasourceId(targetId);
        task.setSyncStatus("0");
        task.setTimestampField("time_stamp");
        return task;
    }

    private static class Fixture
    {
        private final ErCombineSyncServiceImpl service = new ErCombineSyncServiceImpl();
        private final ErCombineSync oldTask;
        private ErCombineSync saved;
        private int datasourceReads;

        Fixture(Long sourceId, Long targetId) throws Exception
        {
            oldTask = task(sourceId, targetId);
            inject("erCombineSyncMapper", Proxy.newProxyInstance(ErCombineSyncMapper.class.getClassLoader(),
                    new Class<?>[] {ErCombineSyncMapper.class}, (proxy, method, args) -> {
                        if ("selectErCombineSyncByCombineId".equals(method.getName())) return oldTask;
                        if ("insertErCombineSync".equals(method.getName()) || "updateErCombineSync".equals(method.getName()))
                        {
                            saved = (ErCombineSync) args[0];
                            return 1;
                        }
                        throw new AssertionError("Unexpected mapper method: " + method.getName());
                    }));
            inject("erDatasourceMapper", Proxy.newProxyInstance(ErDatasourceMapper.class.getClassLoader(),
                    new Class<?>[] {ErDatasourceMapper.class}, (proxy, method, args) -> {
                        datasourceReads++;
                        throw new AssertionError("Datasource lookup was not expected: " + method.getName());
                    }));
        }

        private void assertNoSideEffects()
        {
            assertNull(saved);
            assertEquals(0, datasourceReads);
        }

        private void inject(String name, Object value) throws Exception
        {
            Field field = ErCombineSyncServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        }
    }
}
