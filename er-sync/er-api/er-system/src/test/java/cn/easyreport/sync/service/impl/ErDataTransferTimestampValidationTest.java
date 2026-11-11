package cn.easyreport.sync.service.impl;

import cn.easyreport.common.exception.ServiceException;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.Assert.*;

/** Validates effective incremental settings before saving or claiming a task. */
public class ErDataTransferTimestampValidationTest {
    private static final String REQUIRED = "增量同步必须填写时间戳字段";

    @Test
    public void incrementalInsertRejectsMissingAndBlankTimestampBeforeWrites() throws Exception {
        for (String timestamp : new String[] {null, "", " \t\r\n "}) {
            Fixture f = new Fixture();
            ErDataTransfer task = f.task("1");
            task.setTimestampField(timestamp);

            rejects(() -> f.service.insertErDataTransfer(task));

            assertEquals(0, f.writes);
            assertEquals(0, f.datasourceReads);
        }
    }

    @Test
    public void incrementalInsertTrimsAndSavesExplicitTimestamp() throws Exception {
        Fixture f = new Fixture();
        ErDataTransfer task = f.task("1");
        task.setTimestampField(" modified_at ");

        assertEquals(1, f.service.insertErDataTransfer(task));

        assertEquals("modified_at", f.saved.getTimestampField());
        assertEquals(1, f.writes);
    }

    @Test
    public void initializationInsertAndUpdateKeepTimestampOptional() throws Exception {
        for (String timestamp : new String[] {null, "", " \t "}) {
            Fixture f = new Fixture();
            ErDataTransfer task = f.task("0");
            task.setTimestampField(timestamp);

            assertEquals(1, f.service.insertErDataTransfer(task));
            assertEquals(1, f.service.updateErDataTransfer(task));

            assertEquals(timestamp == null ? null : "", f.saved.getTimestampField());
            assertEquals(2, f.writes);
        }
    }

    @Test
    public void sparseIncrementalUpdateUsesSavedTimestampWithoutCopyingOmittedFields() throws Exception {
        Fixture f = new Fixture();
        f.oldTask.setSyncMode("1");
        f.oldTask.setTimestampField("modified_at");
        ErDataTransfer patch = f.patch();
        patch.setStatus("1");

        assertEquals(1, f.service.updateErDataTransfer(patch));

        assertNull(f.saved.getSyncMode());
        assertNull(f.saved.getTimestampField());
        assertEquals("1", f.saved.getStatus());
        assertEquals(1, f.writes);
        assertEquals(0, f.datasourceReads);
    }

    @Test
    public void sparseIncrementalUpdateCannotExplicitlyClearSavedTimestamp() throws Exception {
        for (String timestamp : new String[] {"", " \t "}) {
            Fixture f = new Fixture();
            f.oldTask.setSyncMode("1");
            f.oldTask.setTimestampField("modified_at");
            ErDataTransfer patch = f.patch();
            patch.setTimestampField(timestamp);

            rejects(() -> f.service.updateErDataTransfer(patch));

            assertEquals(0, f.writes);
            assertEquals("modified_at", f.oldTask.getTimestampField());
        }
    }

    @Test
    public void switchingToIncrementalRequiresEffectiveTimestamp() throws Exception {
        Fixture f = new Fixture();
        ErDataTransfer patch = f.patch();
        patch.setSyncMode("1");

        rejects(() -> f.service.updateErDataTransfer(patch));
        assertEquals(0, f.writes);

        f.oldTask.setTimestampField("modified_at");
        assertEquals(1, f.service.updateErDataTransfer(patch));
        assertEquals("1", f.saved.getSyncMode());
        assertNull(f.saved.getTimestampField());
    }

    @Test
    public void switchingToInitializationAllowsClearingIncrementalTimestamp() throws Exception {
        Fixture f = new Fixture();
        f.oldTask.setSyncMode("1");
        f.oldTask.setTimestampField("modified_at");
        ErDataTransfer patch = f.patch();
        patch.setSyncMode("0");
        patch.setTimestampField(" ");

        assertEquals(1, f.service.updateErDataTransfer(patch));

        assertEquals("0", f.saved.getSyncMode());
        assertEquals("", f.saved.getTimestampField());
    }

    @Test
    public void historicalInvalidIncrementalTaskIsRejectedBeforeDatasourceLookupOrClaim() throws Exception {
        for (String timestamp : new String[] {null, "", " \t\r\n "}) {
            Fixture f = new Fixture();
            f.oldTask.setSyncMode("1");
            f.oldTask.setTimestampField(timestamp);

            Map<String, Object> result = f.service.executeTransfer(7L);

            assertEquals(Boolean.FALSE, result.get("success"));
            assertEquals(REQUIRED, result.get("message"));
            assertEquals(0, f.datasourceReads);
            assertEquals(0, f.claims);
            assertEquals(0, f.writes);
        }
    }

    @Test
    public void validIncrementalTaskPassesTimestampGuardBeforeDatasourceCheck() throws Exception {
        Fixture f = new Fixture();
        f.oldTask.setSyncMode("1");
        f.oldTask.setTimestampField(" modified_at ");

        Map<String, Object> result = f.service.executeTransfer(7L);

        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("源或目标数据源未配置", result.get("message"));
        assertEquals(2, f.datasourceReads);
        assertEquals(0, f.claims);
        assertEquals("modified_at", f.oldTask.getTimestampField());
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            fail("Expected missing incremental timestamp to reject the task");
        } catch (ServiceException expected) {
            assertEquals(REQUIRED, expected.getMessage());
        }
    }

    private static final class Fixture {
        final ErDataTransferServiceImpl service = new ErDataTransferServiceImpl();
        final ErDataTransfer oldTask = task("0");
        ErDataTransfer saved;
        int writes;
        int datasourceReads;
        int claims;

        Fixture() throws Exception {
            set(service, "erDataTransferMapper", Proxy.newProxyInstance(
                    ErDataTransferMapper.class.getClassLoader(), new Class<?>[] {ErDataTransferMapper.class},
                    (object, method, args) -> {
                        switch (method.getName()) {
                            case "selectErDataTransferByTransferId": return oldTask;
                            case "insertErDataTransfer":
                            case "updateErDataTransfer":
                                writes++;
                                saved = (ErDataTransfer) args[0];
                                return 1;
                            case "claimTransfer": claims++; return 1;
                            default: throw new AssertionError("Unexpected task mapper call: " + method.getName());
                        }
                    }));
            set(service, "erDatasourceMapper", Proxy.newProxyInstance(
                    ErDatasourceMapper.class.getClassLoader(), new Class<?>[] {ErDatasourceMapper.class},
                    (object, method, args) -> {
                        if (!"selectErDatasourceByDatasourceId".equals(method.getName())) {
                            throw new AssertionError("Unexpected datasource mapper call: " + method.getName());
                        }
                        datasourceReads++;
                        return null;
                    }));
        }

        ErDataTransfer task(String mode) {
            ErDataTransfer task = patch();
            task.setSyncMode(mode);
            task.setSyncStatus("0");
            task.setExternalTableEnabled("0");
            task.setSourceDatasourceId(1L);
            task.setTargetDatasourceId(2L);
            return task;
        }

        ErDataTransfer patch() {
            ErDataTransfer patch = new ErDataTransfer();
            patch.setTransferId(7L);
            return patch;
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
