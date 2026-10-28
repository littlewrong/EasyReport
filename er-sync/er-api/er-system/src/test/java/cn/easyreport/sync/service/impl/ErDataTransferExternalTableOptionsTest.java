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
        assertNull(task.getDirectImportMethod());
        assertNull(task.getExternalTableThresholdWan());
        assertNull(task.getExternalTableWindowUnit());

        fixture.service.insertErDataTransfer(task);

        assertEquals("0", fixture.saved.getExternalTableEnabled());
        assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, fixture.saved.getDirectImportMethod());
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
    public void explicitlyEnablesSupportedCatalogSources() throws Exception
    {
        for (String sourceType : new String[] {"MYSQL", "TIDB", "mysql", "tidb", "POSTGRESQL", "ORACLE", "SQLSERVER", "STARROCKS", "starrocks"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");
            task.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG);

            fixture.service.insertErDataTransfer(task);

            assertEquals("1", fixture.saved.getExternalTableEnabled());
            assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, fixture.saved.getDirectImportMethod());
            assertEquals(2, fixture.datasourceReads);
        }
    }

    @Test
    public void starRocksAndOracleSaveTheirCatalogImportPolicy() throws Exception
    {
        for (String sourceType : new String[] {"STARROCKS", "ORACLE"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");
            task.setCatalogName("src_" + sourceType.toLowerCase(java.util.Locale.ROOT));
            task.setExternalTableThresholdWan(75);
            task.setExternalTableWindowUnit("MONTH");
            task.setBatchSize(3000);

            fixture.service.insertErDataTransfer(task);

            assertEquals("1", fixture.saved.getExternalTableEnabled());
            assertEquals(task.getCatalogName(), fixture.saved.getCatalogName());
            assertEquals(Integer.valueOf(75), fixture.saved.getExternalTableThresholdWan());
            assertEquals("MONTH", fixture.saved.getExternalTableWindowUnit());
            assertEquals(Integer.valueOf(3000), fixture.saved.getBatchSize());
        }
    }

    @Test
    public void starRocksAndOracleRequireCatalogWhenExplicitlyEnabled() throws Exception
    {
        for (String sourceType : new String[] {"STARROCKS", "ORACLE"})
        {
            for (String name : new String[] {null, "", "  "})
            {
                Fixture fixture = new Fixture(sourceType, "STARROCKS");
                fixture.oldTask.setCatalogName(null);
                ErDataTransfer task = fixture.task();
                task.setExternalTableEnabled("1");
                task.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG);
                task.setCatalogName(name);

                expectRejected(fixture, task, false);
                expectRejected(fixture, task, true);
            }
        }
    }

    @Test
    public void starRocksAndOracleCannotEnableCatalogForAnotherTargetOrIncrementalMode() throws Exception
    {
        for (String sourceType : new String[] {"STARROCKS", "ORACLE"})
        {
            Fixture anotherTarget = new Fixture(sourceType, "MYSQL");
            ErDataTransfer task = anotherTarget.task();
            task.setExternalTableEnabled("1");
            expectRejected(anotherTarget, task, false);

            Fixture incremental = new Fixture(sourceType, "STARROCKS");
            task = incremental.task();
            task.setSyncMode("1");
            task.setExternalTableEnabled("1");
            expectRejected(incremental, task, false);
        }
    }

    @Test
    public void starRocksAndOracleBindingChangesDisableCatalogWithoutExplicitEnable() throws Exception
    {
        for (String sourceType : new String[] {"STARROCKS", "ORACLE"})
        {
            for (boolean changeTarget : new boolean[] {false, true})
            {
                Fixture fixture = new Fixture(sourceType, "STARROCKS");
                fixture.oldTask.setExternalTableEnabled("1");
                ErDataTransfer patch = new ErDataTransfer();
                patch.setTransferId(7L);
                if (changeTarget)
                {
                    ErDatasource anotherTarget = new ErDatasource();
                    anotherTarget.setDatasourceType("MYSQL");
                    fixture.sources.put(3L, anotherTarget);
                    patch.setTargetDatasourceId(3L);
                }
                else
                {
                    patch.setSyncMode("1");
                }

                fixture.service.updateErDataTransfer(patch);

                assertEquals("0", fixture.saved.getExternalTableEnabled());
                assertNull(fixture.saved.getCatalogName());
                assertNull(fixture.saved.getExternalTableThresholdWan());
                assertNull(fixture.saved.getExternalTableWindowUnit());
            }
        }
    }

    @Test
    public void starRocksAndOracleStatusPatchesPreserveCatalogOptionsWithoutDatasourceReads() throws Exception
    {
        for (String sourceType : new String[] {"STARROCKS", "ORACLE"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            fixture.oldTask.setExternalTableEnabled("1");
            fixture.oldTask.setCatalogName("src_existing");
            fixture.oldTask.setExternalTableThresholdWan(75);
            fixture.oldTask.setExternalTableWindowUnit("MONTH");
            ErDataTransfer patch = new ErDataTransfer();
            patch.setTransferId(7L);
            patch.setSyncStatus("1");
            patch.setSyncProgress("{\"current\":1}");

            fixture.service.updateErDataTransfer(patch);

            assertNull(fixture.saved.getExternalTableEnabled());
            assertNull(fixture.saved.getCatalogName());
            assertNull(fixture.saved.getExternalTableThresholdWan());
            assertNull(fixture.saved.getExternalTableWindowUnit());
            assertEquals(0, fixture.datasourceReads);
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
        for (String[] types : new String[][] {{"DORIS", "STARROCKS"}, {"TIDB", "MYSQL"}})
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
        assertNull(fixture.saved.getDirectImportMethod());
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
        assertNull(fixture.saved.getDirectImportMethod());
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
        unsupported.setDatasourceType("DORIS");
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
        assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, fixture.saved.getDirectImportMethod());
        assertEquals(2, fixture.datasourceReads);
    }

    @Test
    public void catalogNameHasNoJavaDefaultAndIsTrimmedWhenSaved() throws Exception
    {
        assertNull(new ErDataTransfer().getCatalogName());
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        ErDataTransfer task = fixture.task();
        task.setExternalTableEnabled("1");
        task.setCatalogName("  src_mysql_2  ");

        fixture.service.insertErDataTransfer(task);

        assertEquals("src_mysql_2", fixture.saved.getCatalogName());
    }

    @Test
    public void rejectsEnabledTaskWithMissingOrUnsafeCatalogName() throws Exception
    {
        for (String name : new String[] {null, "", "  ", "1src", "_src", "src.db", "src-name", "src`name", "src;DROP", "数据源", repeat('a', 1024)})
        {
            Fixture fixture = new Fixture("MYSQL", "STARROCKS");
            fixture.oldTask.setCatalogName(null);
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");
            task.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG);
            task.setCatalogName(name);
            expectRejected(fixture, task, false);
            expectRejected(fixture, task, true);
        }
    }

    @Test
    public void acceptsCatalogNameAtIdentifierLengthLimit() throws Exception
    {
        Fixture fixture = new Fixture("POSTGRESQL", "STARROCKS");
        ErDataTransfer task = fixture.task();
        task.setExternalTableEnabled("1");
        task.setCatalogName(repeat('a', 1023));

        fixture.service.insertErDataTransfer(task);

        assertEquals(1023, fixture.saved.getCatalogName().length());
    }

    @Test
    public void legacyProgressAndStatusPatchesDoNotRequireOrWriteCatalog() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        fixture.oldTask.setCatalogName(null);
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setSyncStatus("1");
        patch.setSyncProgress("{\"current\":5}");
        patch.setStatus("0");

        fixture.service.updateErDataTransfer(patch);

        assertNull(fixture.saved.getCatalogName());
        assertNull(fixture.saved.getExternalTableEnabled());
        assertNull(fixture.saved.getDirectImportMethod());
        assertNull(fixture.saved.getExternalTableThresholdWan());
        assertNull(fixture.saved.getExternalTableWindowUnit());
        assertEquals(0, fixture.datasourceReads);
    }

    @Test
    public void editingEnabledLegacyConfigurationKeepsExternalTablesWithoutCatalog() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        fixture.oldTask.setCatalogName(null);
        ErDataTransfer patch = fixture.task();
        patch.setCatalogName(null);

        fixture.service.updateErDataTransfer(patch);

        assertNull(fixture.saved.getExternalTableEnabled());
        assertNull(fixture.saved.getCatalogName());
        assertEquals(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE, fixture.saved.getDirectImportMethod());
        assertEquals(2, fixture.datasourceReads);
    }

    @Test
    public void enabledCatalogCannotBeExplicitlyClearedWithSparsePatch() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setCatalogName("  ");

        expectRejected(fixture, patch, true);
    }

    @Test
    public void switchOnlyPatchCanReuseSavedCatalogWithoutOverwritingIt() throws Exception
    {
        Fixture fixture = new Fixture("SQLSERVER", "STARROCKS");
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setExternalTableEnabled("1");

        fixture.service.updateErDataTransfer(patch);

        assertEquals("1", fixture.saved.getExternalTableEnabled());
        assertNull(fixture.saved.getCatalogName());
        assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, fixture.saved.getDirectImportMethod());
    }

    @Test
    public void legacyClientInsertInfersRouteFromCatalogName() throws Exception
    {
        for (String catalog : new String[] {null, "", "  ", " src_mysql "})
        {
            Fixture fixture = new Fixture("MYSQL", "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");
            task.setCatalogName(catalog);

            fixture.service.insertErDataTransfer(task);

            assertEquals(catalog != null && !catalog.trim().isEmpty()
                    ? ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG : ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE,
                    fixture.saved.getDirectImportMethod());
        }
    }

    @Test
    public void explicitExternalTablesAcceptMysqlAndTidbWithInactiveCatalogNames() throws Exception
    {
        for (String sourceType : new String[] {"MYSQL", "TIDB", "mysql", "tidb"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");
            task.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE);
            task.setCatalogName("src_retained");

            fixture.service.insertErDataTransfer(task);

            assertEquals(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE, fixture.saved.getDirectImportMethod());
            assertEquals("src_retained", fixture.saved.getCatalogName());
            assertEquals("1", fixture.saved.getExternalTableEnabled());
        }
    }

    @Test
    public void explicitExternalTablesRejectOtherCatalogSourcesOnInsertAndUpdate() throws Exception
    {
        for (String sourceType : new String[] {"POSTGRESQL", "ORACLE", "SQLSERVER", "STARROCKS"})
        {
            Fixture fixture = new Fixture(sourceType, "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("1");
            task.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE);
            expectRejected(fixture, task, false);
            expectRejected(fixture, task, true);
        }
    }

    @Test
    public void bothExplicitMethodsRequireFullSyncAndStarRocksTarget() throws Exception
    {
        for (String method : new String[] {ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE,
                ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG})
        {
            Fixture wrongTarget = new Fixture("MYSQL", "POSTGRESQL");
            ErDataTransfer task = wrongTarget.task();
            task.setExternalTableEnabled("1");
            task.setDirectImportMethod(method);
            expectRejected(wrongTarget, task, false);

            Fixture incremental = new Fixture("MYSQL", "STARROCKS");
            task = incremental.task();
            task.setSyncMode("1");
            task.setExternalTableEnabled("1");
            task.setDirectImportMethod(method);
            expectRejected(incremental, task, true);
        }
    }

    @Test
    public void unknownMethodsAreRejectedEvenWhenDirectImportIsDisabled() throws Exception
    {
        for (String method : new String[] {"", "  ", "CATALOG", "external_table", "JDBC", "2"})
        {
            Fixture fixture = new Fixture("MYSQL", "STARROCKS");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("0");
            task.setDirectImportMethod(method);
            expectRejected(fixture, task, false);
            expectRejected(fixture, task, true);
            assertEquals(0, fixture.datasourceReads);
        }
    }

    @Test
    public void disabledImportsCanStoreChoiceWithoutRequiringCatalogOrSupportedBinding() throws Exception
    {
        for (String method : new String[] {ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE,
                ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG})
        {
            Fixture fixture = new Fixture("POSTGRESQL", "MYSQL");
            ErDataTransfer task = fixture.task();
            task.setExternalTableEnabled("0");
            task.setDirectImportMethod(" " + method + " ");
            task.setCatalogName(null);

            fixture.service.insertErDataTransfer(task);

            assertEquals(method, fixture.saved.getDirectImportMethod());
            assertEquals(0, fixture.datasourceReads);
        }
    }

    @Test
    public void editingLegacyExternalNameDoesNotImplicitlySwitchToCatalog() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        fixture.oldTask.setCatalogName(null);
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setCatalogName("src_saved_for_later");

        fixture.service.updateErDataTransfer(patch);

        assertEquals(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE, fixture.saved.getDirectImportMethod());
        assertEquals("src_saved_for_later", fixture.saved.getCatalogName());
        assertNull(fixture.saved.getExternalTableEnabled());
    }

    @Test
    public void editingExplicitExternalRouteIgnoresRetainedCatalogName() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        fixture.oldTask.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE);
        ErDataTransfer patch = fixture.task();
        patch.setCatalogName(null);

        fixture.service.updateErDataTransfer(patch);

        assertEquals(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE, fixture.saved.getDirectImportMethod());
        assertNull(fixture.saved.getCatalogName());
        assertNull(fixture.saved.getExternalTableEnabled());
        assertEquals(2, fixture.datasourceReads);
    }

    @Test
    public void explicitMethodSwitchCanKeepInactiveCatalogOrSupplyRequiredCatalog() throws Exception
    {
        Fixture catalogToExternal = new Fixture("MYSQL", "STARROCKS");
        catalogToExternal.oldTask.setExternalTableEnabled("1");
        catalogToExternal.oldTask.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG);
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE);

        catalogToExternal.service.updateErDataTransfer(patch);

        assertEquals(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE, catalogToExternal.saved.getDirectImportMethod());
        assertNull(catalogToExternal.saved.getCatalogName());
        assertNull(catalogToExternal.saved.getExternalTableEnabled());

        Fixture externalToCatalog = new Fixture("MYSQL", "STARROCKS");
        externalToCatalog.oldTask.setExternalTableEnabled("1");
        externalToCatalog.oldTask.setCatalogName(null);
        externalToCatalog.oldTask.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE);
        patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG);
        expectRejected(externalToCatalog, patch, true);
        patch.setCatalogName(" src_explicit ");

        externalToCatalog.service.updateErDataTransfer(patch);

        assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, externalToCatalog.saved.getDirectImportMethod());
        assertEquals("src_explicit", externalToCatalog.saved.getCatalogName());
    }

    @Test
    public void explicitExternalMethodCannotBeSavedForEnabledOracleTask() throws Exception
    {
        Fixture fixture = new Fixture("ORACLE", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        fixture.oldTask.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG);
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE);

        expectRejected(fixture, patch, true);
    }

    @Test
    public void inheritedExternalRouteDoesNotBecomeCatalogWhenChangingSource() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        fixture.oldTask.setDirectImportMethod(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE);
        ErDatasource oracle = new ErDatasource();
        oracle.setDatasourceType("ORACLE");
        fixture.sources.put(3L, oracle);
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setSourceDatasourceId(3L);

        fixture.service.updateErDataTransfer(patch);

        assertEquals("0", fixture.saved.getExternalTableEnabled());
        assertEquals(ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE, fixture.saved.getDirectImportMethod());
        assertNull(fixture.saved.getCatalogName());
    }

    @Test
    public void automaticallyDisablingLegacyCatalogKeepsItsRouteWhenNameIsCleared() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        ErDatasource anotherTarget = new ErDatasource();
        anotherTarget.setDatasourceType("MYSQL");
        fixture.sources.put(3L, anotherTarget);
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setTargetDatasourceId(3L);
        patch.setCatalogName("");

        fixture.service.updateErDataTransfer(patch);

        assertEquals("0", fixture.saved.getExternalTableEnabled());
        assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, fixture.saved.getDirectImportMethod());
        assertEquals("", fixture.saved.getCatalogName());
        assertEquals(Long.valueOf(3L), fixture.saved.getTargetDatasourceId());
        assertEquals(2, fixture.datasourceReads);
    }

    @Test
    public void statusAndPolicyPatchesDoNotFillOrOverwriteExplicitStoredMethods() throws Exception
    {
        for (String method : new String[] {ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE,
                ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG})
        {
            Fixture fixture = new Fixture("MYSQL", "STARROCKS");
            fixture.oldTask.setExternalTableEnabled("1");
            fixture.oldTask.setDirectImportMethod(method);
            ErDataTransfer patch = new ErDataTransfer();
            patch.setTransferId(7L);
            patch.setSyncStatus("1");
            patch.setSyncProgress("{\"current\":8}");

            fixture.service.updateErDataTransfer(patch);

            assertNull(fixture.saved.getDirectImportMethod());
            assertNull(fixture.saved.getExternalTableEnabled());
            assertNull(fixture.saved.getCatalogName());
            assertEquals(0, fixture.datasourceReads);

            patch = new ErDataTransfer();
            patch.setTransferId(7L);
            patch.setExternalTableWindowUnit("DAY");
            fixture.service.updateErDataTransfer(patch);
            assertNull(fixture.saved.getDirectImportMethod());
            assertNull(fixture.saved.getExternalTableEnabled());
            assertNull(fixture.saved.getCatalogName());
            assertEquals(0, fixture.datasourceReads);
        }
    }

    @Test
    public void disablingLegacyCatalogWhileClearingItsNameKeepsCatalogForNextEnable() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("1");
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setExternalTableEnabled("0");
        patch.setCatalogName("");

        fixture.service.updateErDataTransfer(patch);

        assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, fixture.saved.getDirectImportMethod());
        assertEquals("0", fixture.saved.getExternalTableEnabled());
        assertEquals("", fixture.saved.getCatalogName());
        assertEquals(0, fixture.datasourceReads);

        // Emulate the saved configuration and verify that enabling does not choose external tables.
        fixture.oldTask.setDirectImportMethod(fixture.saved.getDirectImportMethod());
        fixture.oldTask.setExternalTableEnabled("0");
        fixture.oldTask.setCatalogName("");
        fixture.saved = null;
        patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setExternalTableEnabled("1");
        expectRejected(fixture, patch, true);
        assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, patch.getDirectImportMethod());
    }

    @Test
    public void editingDisabledLegacyCatalogNamePinsTheOriginalRoute() throws Exception
    {
        for (String catalog : new String[] {null, "src_original"})
        {
            for (String submittedSwitch : new String[] {null, "0"})
            {
                Fixture fixture = new Fixture("MYSQL", "STARROCKS");
                fixture.oldTask.setExternalTableEnabled("0");
                fixture.oldTask.setCatalogName(catalog);
                ErDataTransfer patch = new ErDataTransfer();
                patch.setTransferId(7L);
                patch.setExternalTableEnabled(submittedSwitch);
                patch.setCatalogName(catalog == null ? "src_for_later" : "");

                fixture.service.updateErDataTransfer(patch);

                assertEquals(catalog == null ? ErDataTransfer.DIRECT_IMPORT_EXTERNAL_TABLE
                        : ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, fixture.saved.getDirectImportMethod());
                assertEquals(submittedSwitch, fixture.saved.getExternalTableEnabled());
                assertEquals(patch.getCatalogName(), fixture.saved.getCatalogName());
                assertEquals(0, fixture.datasourceReads);
            }
        }
    }

    @Test
    public void disabledBindingEditsPinLegacyModeWithoutValidatingInactiveBindings() throws Exception
    {
        for (boolean alreadyDisabled : new boolean[] {false, true})
        {
            for (int changedField = 0; changedField < 3; changedField++)
            {
                Fixture fixture = new Fixture("MYSQL", "STARROCKS");
                fixture.oldTask.setExternalTableEnabled(alreadyDisabled ? "0" : "1");
                ErDataTransfer patch = new ErDataTransfer();
                patch.setTransferId(7L);
                if (!alreadyDisabled) patch.setExternalTableEnabled("0");
                if (changedField == 0) patch.setSourceDatasourceId(99L);
                else if (changedField == 1) patch.setTargetDatasourceId(99L);
                else patch.setSyncMode("1");

                fixture.service.updateErDataTransfer(patch);

                assertEquals(ErDataTransfer.DIRECT_IMPORT_JDBC_CATALOG, fixture.saved.getDirectImportMethod());
                assertNull(fixture.saved.getCatalogName());
                assertEquals(0, fixture.datasourceReads);
            }
        }
    }

    @Test
    public void disabledConfigurationEditsRejectAnUnknownStoredMethodWithoutSourceLookup() throws Exception
    {
        Fixture fixture = new Fixture("MYSQL", "STARROCKS");
        fixture.oldTask.setExternalTableEnabled("0");
        fixture.oldTask.setDirectImportMethod("UNKNOWN");
        ErDataTransfer patch = new ErDataTransfer();
        patch.setTransferId(7L);
        patch.setCatalogName("");

        expectRejected(fixture, patch, true);
        assertEquals(0, fixture.datasourceReads);
    }

    private String repeat(char value, int count)
    {
        char[] characters = new char[count];
        java.util.Arrays.fill(characters, value);
        return new String(characters);
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
            assertTrue(expected.getMessage(), expected.getMessage().contains("导入"));
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
            task.setCatalogName("src_mysql");
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
