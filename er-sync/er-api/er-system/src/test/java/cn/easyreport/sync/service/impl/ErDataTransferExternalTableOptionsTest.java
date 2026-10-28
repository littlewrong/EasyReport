package cn.easyreport.sync.service.impl;

import cn.easyreport.common.exception.ServiceException;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.*;

public class ErDataTransferExternalTableOptionsTest
{
    @Test
    public void newTaskDefaultsToJdbcWithoutLookingUpSources() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        ErDataTransfer task = fixture.task();
        assertNull(task.getExternalTableEnabled());
        assertNull(task.getExternalTableThresholdWan());
        assertNull(task.getExternalTableWindowUnit());

        fixture.service.insertErDataTransfer(task);

        assertEquals("0", fixture.saved.getExternalTableEnabled());
        assertEquals(Integer.valueOf(50), fixture.saved.getExternalTableThresholdWan());
        assertEquals("YEAR", fixture.saved.getExternalTableWindowUnit());
        assertEquals(0, fixture.datasourceReads);
    }

    @Test
    public void storesCustomPoliciesAndKeepsOriginalJdbcBatchSize() throws Exception
    {
        for (String unit : new String[] {"YEAR", "MONTH", "DAY"})
        {
            Fixture fixture = new Fixture("TIDB", "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");
            task.setExternalTableThresholdWan(75);
            task.setExternalTableWindowUnit(unit);
            task.setBatchSize(1000);

            fixture.service.insertErDataTransfer(task);

            assertEquals(Integer.valueOf(75), fixture.saved.getExternalTableThresholdWan());
            assertEquals(unit, fixture.saved.getExternalTableWindowUnit());
            assertEquals(Integer.valueOf(1000), fixture.saved.getBatchSize());
        }
    }

    @Test
    public void acceptsThresholdIntegerBounds() throws Exception
    {
        for (int threshold : new int[] {1, Integer.MAX_VALUE})
        {
            Fixture fixture = new Fixture("MYSQL", "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableThresholdWan(threshold);

            fixture.service.insertErDataTransfer(task);

            assertEquals(Integer.valueOf(threshold), fixture.saved.getExternalTableThresholdWan());
            assertEquals("YEAR", fixture.saved.getExternalTableWindowUnit());
        }
    }

    @Test
    public void rejectsNonPositiveThresholdOnInsertAndUpdate() throws Exception
    {
        for (int threshold : new int[] {0, -1, Integer.MIN_VALUE})
        {
            Fixture fixture = new Fixture("MYSQL", "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableThresholdWan(threshold);
            expectRejected(fixture, task, false);
            expectRejected(fixture, task, true);
        }
    }

    @Test
    public void rejectsUnknownWindowUnitOnInsertAndUpdate() throws Exception
    {
        for (String unit : new String[] {"", "year", "month", "MONTHS", " DAY ", "WEEK"})
        {
            Fixture fixture = new Fixture("MYSQL", "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableWindowUnit(unit);
            expectRejected(fixture, task, false);
            expectRejected(fixture, task, true);
        }
    }

    @Test
    public void fillsOnlyMissingNewTaskPolicy() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        ErDataTransfer task = fixture.task();
        task.setExternalTableWindowUnit("MONTH");

        fixture.service.insertErDataTransfer(task);

        assertEquals(Integer.valueOf(50), fixture.saved.getExternalTableThresholdWan());
        assertEquals("MONTH", fixture.saved.getExternalTableWindowUnit());
    }

    @Test
    public void policyOnlyPatchDoesNotFillOmittedPolicyOrJdbcBatchSize() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        fixture.oldTask.setExternalTableThresholdWan(75);
        fixture.oldTask.setExternalTableWindowUnit("MONTH");
        fixture.oldTask.setBatchSize(1000);
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setExternalTableThresholdWan(25);

        fixture.service.updateErDataTransfer(patch);

        assertEquals(Integer.valueOf(25), fixture.saved.getExternalTableThresholdWan());
        assertNull(fixture.saved.getExternalTableWindowUnit());
        assertNull(fixture.saved.getExternalTableEnabled());
        assertNull(fixture.saved.getBatchSize());
        assertEquals(0, fixture.datasourceReads);

        patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setExternalTableWindowUnit("DAY");
        fixture.service.updateErDataTransfer(patch);
        assertNull(fixture.saved.getExternalTableThresholdWan());
        assertEquals("DAY", fixture.saved.getExternalTableWindowUnit());
        assertNull(fixture.saved.getBatchSize());
    }

    @Test
    public void explicitlyEnablesMysqlAndTidbFullImports() throws Exception
    {
        for (String sourceType : new String[] {"MYSQL", "TIDB", "mysql", "tidb"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");

            fixture.service.insertErDataTransfer(task);

            assertEquals("1", fixture.saved.getExternalTableEnabled());
            assertEquals(2, fixture.datasourceReads);
        }
    }

    @Test
    public void explicitlyEnablesUsingDefaultFullSyncMode() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        ErDataTransfer task = fixture.task();
        task.setSyncMode(null);
        task.setExternalTableEnabled("1");

        fixture.service.insertErDataTransfer(task);

        assertEquals("1", fixture.saved.getExternalTableEnabled());
    }

    @Test
    public void rejectsUnknownSwitchValues() throws Exception
    {
        for (String value : new String[] {"", "true", "2", " 1 "})
        {
            Fixture fixture = new Fixture("MYSQL", "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled(value);
            expectRejected(fixture, task, false);
        }
    }

    @Test
    public void rejectsExplicitEnableForUnsupportedBindings() throws Exception
    {
        for (String[] types : new String[][] {{"POSTGRESQL", "STARROCKS"}, {"ORACLE", "STARROCKS"},
                {"SQLSERVER", "STARROCKS"}, {"STARROCKS", "STARROCKS"}, {"TIDB", "MYSQL"}})
        {
            Fixture fixture = new Fixture(types[0], types[1]);
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");
            expectRejected(fixture, task, false);
        }
        Fixture incremental = new Fixture("TIDB", "STARROCKS");
        ErDataTransfer task = incremental.task();
        task.setSyncMode("1");
        task.setExternalTableEnabled("1");
        expectRejected(incremental, task, false);
    }

    @Test
    public void progressOnlyUpdatePreservesEnabledConfiguration() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        fixture.oldTask.setExternalTableThresholdWan(75);
        fixture.oldTask.setExternalTableWindowUnit("MONTH");
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setSyncStatus("1");
        patch.setSyncProgress("{\"current\":1}");

        fixture.service.updateErDataTransfer(patch);

        assertNull(fixture.saved.getExternalTableEnabled());
        assertNull(fixture.saved.getExternalTableThresholdWan());
        assertNull(fixture.saved.getExternalTableWindowUnit());
        assertEquals(0, fixture.datasourceReads);
    }

    @Test
    public void canExplicitlyDisableWithoutChangingOtherOptions() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setExternalTableEnabled("0");

        fixture.service.updateErDataTransfer(patch);

        assertEquals("0", fixture.saved.getExternalTableEnabled());
        assertNull(fixture.saved.getExternalTableThresholdWan());
        assertNull(fixture.saved.getExternalTableWindowUnit());
        assertNull(fixture.saved.getBatchSize());
        assertEquals(0, fixture.datasourceReads);
    }

    @Test
    public void switchOnlyUpdateValidatesSavedDatasourceAndMode() throws Exception
    {
        Fixture fixture = new Fixture("TIDB", "STARROCKS");
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setExternalTableEnabled("1");

        fixture.service.updateErDataTransfer(patch);

        assertEquals("1", fixture.saved.getExternalTableEnabled());
        fixture.oldTask.setSyncMode("1");
        fixture.saved = null;
        expectRejected(fixture, patch, true);
    }

    @Test
    public void changingEnabledTaskToIncrementalTurnsOffImplicitSwitch() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setSyncMode("1");

        fixture.service.updateErDataTransfer(patch);

        assertEquals("0", fixture.saved.getExternalTableEnabled());
    }

    @Test
    public void changingEnabledTaskToUnsupportedSourceTurnsOffImplicitSwitch() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        ErDatasource unsupported = new ErDatasource();
        unsupported.setDatasourceType("POSTGRESQL");
        fixture.sources.put(3L, unsupported);
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setSourceDatasourceId(3L);

        fixture.service.updateErDataTransfer(patch);

        assertEquals("0", fixture.saved.getExternalTableEnabled());
    }

    @Test
    public void compatibleEditWithOmittedSwitchKeepsStoredValue() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        ErDataTransfer patch = fixture.task();

        fixture.service.updateErDataTransfer(patch);

        assertNull(fixture.saved.getExternalTableEnabled());
        assertEquals(2, fixture.datasourceReads);
    }

    @Test
    public void editingPreviouslyEnabledCatalogSourcesRestoresJdbc() throws Exception
    {
        for (String sourceType : new String[] {"POSTGRESQL", "ORACLE", "SQLSERVER", "STARROCKS"})
        {
            for (boolean sparse : new boolean[] {false, true})
            {
                Fixture fixture = new Fixture(sourceType, "STARROCKS");
                fixture.oldTask.setExternalTableEnabled("1");
                ErDataTransfer patch = sparse ? new ErDataTransfer() : fixture.task();
                patch.setTransferId(7L);
                patch.setExternalTableEnabled("1");

                fixture.service.updateErDataTransfer(patch);

                assertEquals("0", fixture.saved.getExternalTableEnabled());
                assertNull(fixture.saved.getExternalTableThresholdWan());
                assertNull(fixture.saved.getExternalTableWindowUnit());
                assertNull(fixture.saved.getBatchSize());
            }
        }
    }

    @Test
    public void implicitEditOfPreviouslyEnabledCatalogSourceRestoresJdbc() throws Exception
    {
        for (String sourceType : new String[] {"POSTGRESQL", "ORACLE", "SQLSERVER", "STARROCKS"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            fixture.oldTask.setExternalTableEnabled("1");

            fixture.service.updateErDataTransfer(fixture.task());

            assertEquals("0", fixture.saved.getExternalTableEnabled());
        }
    }

    @Test
    public void progressOnlyPatchOfPreviouslyEnabledCatalogSourceRemainsSparse() throws Exception
    {
        for (String sourceType : new String[] {"POSTGRESQL", "ORACLE", "SQLSERVER", "STARROCKS"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            fixture.oldTask.setExternalTableEnabled("1");
            ErDataTransfer patch = new ErDataTransfer();
            patch.setTransferId(7L);
            patch.setSyncStatus("1");
            patch.setSyncProgress("{\"current\":1}");

            fixture.service.updateErDataTransfer(patch);

            assertNull(fixture.saved.getExternalTableEnabled());
            assertEquals(0, fixture.datasourceReads);
        }
    }

    @Test
    public void existingDisabledTaskCannotExplicitlyEnableUnsupportedExternalTable() throws Exception
    {
        for (String sourceType : new String[] {"POSTGRESQL", "ORACLE", "SQLSERVER", "STARROCKS"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            fixture.oldTask.setExternalTableEnabled("0");
            ErDataTransfer patch = fixture.task();
            patch.setExternalTableEnabled("1");

            expectRejected(fixture, patch, true);
        }
    }

    private void expectRejected(Fixture fixture, ErDataTransfer task, boolean update)
    {
        try
        {
            if (update) fixture.service.updateErDataTransfer(task);
            else fixture.service.insertErDataTransfer(task);
            fail("Expected unsupported external table configuration to be rejected");
        }
        catch (ServiceException expected)
        {
            assertTrue(expected.getMessage().contains("外部表模式导入"));
            assertNull(fixture.saved);
        }
    }

    private static class Fixture
    {
        private final ErDataTransferServiceImpl service = new ErDataTransferServiceImpl();
        private final Map<Long, ErDatasource> sources = new HashMap<>();
        private final ErDataTransfer oldTask = task();
        private ErDataTransfer saved;
        private int datasourceReads;

        Fixture(String sourceType, String targetType) throws Exception
        {
            ErDatasource source = new ErDatasource();
            source.setDatasourceType(sourceType);
            ErDatasource target = new ErDatasource();
            target.setDatasourceType(targetType);
            sources.put(1L, source);
            sources.put(2L, target);
            inject("erDataTransferMapper", Proxy.newProxyInstance(ErDataTransferMapper.class.getClassLoader(),
                    new Class<?>[] {ErDataTransferMapper.class}, (proxy, method, args) -> {
                        if ("selectErDataTransferByTransferId".equals(method.getName())) return oldTask;
                        if ("insertErDataTransfer".equals(method.getName()) || "updateErDataTransfer".equals(method.getName()))
                        {
                            saved = (ErDataTransfer) args[0];
                            return 1;
                        }
                        throw new AssertionError("Unexpected mapper method: " + method.getName());
                    }));
            inject("erDatasourceMapper", Proxy.newProxyInstance(ErDatasourceMapper.class.getClassLoader(),
                    new Class<?>[] {ErDatasourceMapper.class}, (proxy, method, args) -> {
                        if ("selectErDatasourceByDatasourceId".equals(method.getName()))
                        {
                            datasourceReads++;
                            return sources.get(args[0]);
                        }
                        throw new AssertionError("Unexpected datasource method: " + method.getName());
                    }));
        }

        private ErDataTransfer task()
        {
            ErDataTransfer task = new ErDataTransfer();
            task.setTransferId(7L);
            task.setSourceDatasourceId(1L);
            task.setTargetDatasourceId(2L);
            task.setSyncMode("0");
            return task;
        }

        private void inject(String name, Object value) throws Exception
        {
            Field field = ErDataTransferServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        }
    }
}
