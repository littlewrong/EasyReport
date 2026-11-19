package cn.easyreport.combinesync.service.impl;

import cn.easyreport.common.exception.ServiceException;
import cn.easyreport.combinesync.domain.ErCombineSync;
import cn.easyreport.combinesync.mapper.ErCombineSyncMapper;
import cn.easyreport.quartz.domain.SysJob;
import cn.easyreport.quartz.service.ISysJobService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.junit.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Scheduling failures must reach the save caller; partial edits must keep the existing association. */
public class ErCombineSyncScheduleTest {
    private static final String OLD_CRON = "0 0/5 * * * ?";
    private static final String NEW_CRON = "0 0/2 * * * ?";

    @Test
    public void cronEditUsesPersistedAssociationAndTrimsBeforeBothWrites() throws Exception {
        Fixture f = new Fixture();
        ErCombineSync patch = patch();
        patch.setCronExpression("  " + NEW_CRON + "  ");
        patch.setJobId(999L); // An older editor must not replace the server's current job link.

        assertEquals(1, f.service.updateErCombineSync(patch));

        assertEquals(Long.valueOf(42L), f.task.getJobId());
        assertEquals(NEW_CRON, f.task.getCronExpression());
        assertEquals(NEW_CRON, f.jobs.get(42L).getCronExpression());
        assertEquals("合并同步-原任务", f.jobs.get(42L).getJobName());
        assertEquals("dataSyncTask.executeCombineSyncById(7L)", f.jobs.get(42L).getInvokeTarget());
        assertEquals("1", f.jobs.get(42L).getConcurrent());
        assertEquals(0, f.jobInserts);
        assertEquals(1, f.taskReads);
        assertFalse(f.jobReads.contains(999L));
    }

    @Test
    public void statusOnlyAndRenamePatchesKeepCronAndAssociation() throws Exception {
        Fixture f = new Fixture();
        ErCombineSync pause = patch();
        pause.setStatus("1");
        f.service.updateErCombineSync(pause);
        assertEquals(OLD_CRON, f.jobs.get(42L).getCronExpression());
        assertEquals("合并同步-原任务", f.jobs.get(42L).getJobName());
        assertEquals("1", f.jobs.get(42L).getStatus());
        assertNull(pause.getCronExpression());

        ErCombineSync rename = patch();
        rename.setCombineName("新任务");
        rename.setStatus("0");
        f.service.updateErCombineSync(rename);
        assertEquals("合并同步-新任务", f.jobs.get(42L).getJobName());
        assertEquals("0", f.jobs.get(42L).getStatus());
        assertEquals(OLD_CRON, f.jobs.get(42L).getCronExpression());
        assertEquals(0, f.jobInserts);
    }

    @Test
    public void addingCronCreatesAndLinksAnEnabledJobAndRebuildsAMissingJob() throws Exception {
        for (boolean staleLink : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.jobs.clear();
            if (!staleLink) {
                f.task.setJobId(null);
                f.task.setCronExpression(null);
            }
            ErCombineSync patch = patch();
            patch.setCronExpression(NEW_CRON);
            f.service.updateErCombineSync(patch);
            assertEquals(Long.valueOf(100L), f.task.getJobId());
            assertEquals(NEW_CRON, f.jobs.get(100L).getCronExpression());
            assertEquals("0", f.jobs.get(100L).getStatus());
            assertEquals(1, f.jobInserts);
            assertEquals(1, f.resumes);
        }
    }

    @Test
    public void newDisabledTaskIgnoresClientJobIdAndKeepsCreatedJobPaused() throws Exception {
        Fixture f = new Fixture();
        ErCombineSync task = task();
        task.setJobId(999L);
        task.setCronExpression(" " + NEW_CRON + " ");
        task.setStatus("1");
        assertEquals(1, f.service.insertErCombineSync(task));
        assertEquals(Long.valueOf(100L), f.task.getJobId());
        assertEquals(NEW_CRON, f.task.getCronExpression());
        assertEquals("1", f.jobs.get(100L).getStatus());
        assertEquals(0, f.resumes);
    }

    @Test
    public void clearingCronDeletesWithActualGroupAndClearsAssociationEvenIfJobWasAlreadyDeleted() throws Exception {
        for (boolean jobExists : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            if (jobExists) f.jobs.get(42L).setJobGroup("CUSTOM_GROUP");
            else f.jobs.clear();
            ErCombineSync clear = patch();
            clear.setCronExpression(" \t ");
            f.service.updateErCombineSync(clear);
            assertEquals("", f.task.getCronExpression());
            assertNull(f.task.getJobId());
            assertFalse(f.jobs.containsKey(42L));
            assertEquals(1, f.clearedLinks);
            if (jobExists) assertEquals("CUSTOM_GROUP", f.deletedJobs.get(0).getJobGroup());
            else assertTrue(f.deletedJobs.isEmpty());
        }
    }

    @Test
    public void invalidOrExpiredCronAndUnavailableSchedulerFailBeforeBusinessWrite() throws Exception {
        for (String cron : Arrays.asList("invalid cron", "* * * * *", "0 0 0 1 1 ? 2000")) {
            for (boolean insert : new boolean[]{false, true}) {
                Fixture f = new Fixture();
                ErCombineSync request = insert ? task() : patch();
                request.setCronExpression(cron);
                expectFailure(() -> {
                    if (insert) f.service.insertErCombineSync(request);
                    else f.service.updateErCombineSync(request);
                }, "Cron");
                assertEquals(0, f.businessWrites);
                assertEquals(0, f.jobInserts);
            }
        }
        Fixture missingService = new Fixture();
        inject(missingService.service, "jobService", null);
        ErCombineSync request = patch();
        request.setCronExpression(NEW_CRON);
        expectFailure(() -> missingService.service.updateErCombineSync(request), "不可用");
        assertEquals(0, missingService.businessWrites);
    }

    @Test
    public void schedulerFailuresAreNotReportedAsSuccessfulSaves() throws Exception {
        for (String operation : Arrays.asList("insertJob", "updateJob", "deleteJob", "changeStatus")) {
            Fixture f = new Fixture();
            f.failOperation = operation;
            ErCombineSync request = patch();
            request.setCronExpression("deleteJob".equals(operation) ? "" : NEW_CRON);
            if ("insertJob".equals(operation) || "changeStatus".equals(operation)) {
                f.task.setJobId(null);
                f.jobs.clear();
            }
            expectFailure(() -> f.service.updateErCombineSync(request), "联动失败");
            if ("changeStatus".equals(operation)) assertFalse(f.jobs.containsKey(100L));
        }
    }

    @Test
    public void failedLinkWriteCleansTheNewRamScheduleAndDoesNotReturnSuccess() throws Exception {
        Fixture f = new Fixture();
        f.task.setJobId(null);
        f.jobs.clear();
        f.failOperation = "updateJobId";
        ErCombineSync request = patch();
        request.setCronExpression(NEW_CRON);
        expectFailure(() -> f.service.updateErCombineSync(request), "联动失败");
        assertEquals(1, f.jobInserts);
        assertFalse(f.jobs.containsKey(100L));
        assertEquals("DATA_SYNC", f.deletedJobs.get(0).getJobGroup());
    }

    @Test
    public void missingBusinessTaskDoesNotCreateAnUnrelatedJob() throws Exception {
        Fixture f = new Fixture();
        f.task = null;
        expectFailure(() -> f.service.updateErCombineSync(patch()), "不存在");
        assertEquals(0, f.businessWrites);
        assertEquals(0, f.jobInserts);
    }

    @Test
    public void explicitNullLinkSqlAndSaveTransactionsSupportRollback() throws Exception {
        Configuration config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("ErCombineSync", ErCombineSync.class);
        String resource = "mapper/combinesync/ErCombineSyncMapper.xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        BoundSql sql = config.getMappedStatement(ErCombineSyncMapper.class.getName() + ".updateJobId").getBoundSql(patch());
        assertTrue(sql.getSql(), sql.getSql().replaceAll("\\s+", " ").contains("SET job_id = ? WHERE combine_id = ?"));
        assertEquals(JdbcType.BIGINT, sql.getParameterMappings().get(0).getJdbcType());
        for (String method : Arrays.asList("insertErCombineSync", "updateErCombineSync")) {
            Transactional transaction = ErCombineSyncServiceImpl.class.getMethod(method, ErCombineSync.class).getAnnotation(Transactional.class);
            assertNotNull(transaction);
            assertTrue(Arrays.asList(transaction.rollbackFor()).contains(Exception.class));
        }
    }

    private static ErCombineSync patch() {
        ErCombineSync patch = new ErCombineSync();
        patch.setCombineId(7L);
        return patch;
    }

    private static ErCombineSync task() {
        ErCombineSync task = patch();
        task.setCombineName("原任务");
        task.setSourceDatasourceId(1L);
        task.setTargetDatasourceId(1L);
        task.setCronExpression(OLD_CRON);
        task.setStatus("0");
        task.setJobId(42L);
        return task;
    }

    private static void expectFailure(Runnable operation, String message) {
        try { operation.run(); fail("Save must fail when its schedule cannot be updated"); }
        catch (ServiceException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }

    private static class Fixture {
        final ErCombineSyncServiceImpl service = new ErCombineSyncServiceImpl();
        ErCombineSync task = task();
        final Map<Long, SysJob> jobs = new LinkedHashMap<>();
        final List<Long> jobReads = new ArrayList<>();
        final List<SysJob> deletedJobs = new ArrayList<>();
        int businessWrites, taskReads, jobInserts, resumes, clearedLinks;
        String failOperation;

        Fixture() throws Exception {
            SysJob job = new SysJob();
            job.setJobId(42L);
            job.setJobName("合并同步-原任务");
            job.setJobGroup("DATA_SYNC");
            job.setCronExpression(OLD_CRON);
            job.setStatus("0");
            jobs.put(42L, job);
            inject(service, "erCombineSyncMapper", Proxy.newProxyInstance(ErCombineSyncMapper.class.getClassLoader(),
                new Class<?>[]{ErCombineSyncMapper.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals(failOperation)) throw new ServiceException("injected write failure");
                    if ("selectErCombineSyncByCombineId".equals(name)) {
                        taskReads++;
                        return copy(task, new ErCombineSync());
                    }
                    if ("insertErCombineSync".equals(name)) {
                        businessWrites++;
                        ErCombineSync insert = (ErCombineSync) args[0];
                        insert.setCombineId(7L);
                        task = copy(insert, new ErCombineSync());
                        return 1;
                    }
                    if ("updateErCombineSync".equals(name)) {
                        businessWrites++;
                        ErCombineSync update = (ErCombineSync) args[0];
                        if (update.getCronExpression() != null) task.setCronExpression(update.getCronExpression());
                        if (update.getCombineName() != null && !update.getCombineName().isEmpty()) task.setCombineName(update.getCombineName());
                        if (update.getStatus() != null) task.setStatus(update.getStatus());
                        if (update.getJobId() != null) task.setJobId(update.getJobId());
                        return 1;
                    }
                    if ("updateJobId".equals(name)) {
                        Long jobId = ((ErCombineSync) args[0]).getJobId();
                        task.setJobId(jobId);
                        if (jobId == null) clearedLinks++;
                        return 1;
                    }
                    throw new AssertionError(name);
                }));
            inject(service, "jobService", Proxy.newProxyInstance(ISysJobService.class.getClassLoader(),
                new Class<?>[]{ISysJobService.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals(failOperation)) throw new ServiceException("injected scheduler failure");
                    if ("selectJobById".equals(name)) {
                        jobReads.add((Long) args[0]);
                        return copy(jobs.get(args[0]), new SysJob());
                    }
                    SysJob value = (SysJob) args[0];
                    if ("insertJob".equals(name)) {
                        jobInserts++;
                        value.setJobId(100L);
                        value.setStatus("1");
                        jobs.put(100L, copy(value, new SysJob()));
                        return 1;
                    }
                    if ("updateJob".equals(name)) {
                        jobs.put(value.getJobId(), copy(value, new SysJob()));
                        return 1;
                    }
                    if ("changeStatus".equals(name)) {
                        resumes++;
                        jobs.get(value.getJobId()).setStatus(value.getStatus());
                        return 1;
                    }
                    if ("deleteJob".equals(name)) {
                        deletedJobs.add(copy(value, new SysJob()));
                        return jobs.remove(value.getJobId()) == null ? 0 : 1;
                    }
                    throw new AssertionError(name);
                }));
        }
    }

    private static <T> T copy(T source, T target) {
        if (source == null) return null;
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = ErCombineSyncServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
