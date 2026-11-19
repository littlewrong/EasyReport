package cn.easyreport.combinesync.service.impl;

import cn.easyreport.combinesync.domain.ErCombineSync;
import cn.easyreport.combinesync.mapper.ErCombineSyncMapper;
import cn.easyreport.quartz.domain.SysJob;
import cn.easyreport.quartz.mapper.SysJobMapper;
import cn.easyreport.quartz.service.impl.SysJobServiceImpl;
import cn.easyreport.quartz.util.ScheduleUtils;
import org.junit.Test;
import org.quartz.CronTrigger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.beans.BeanUtils;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.*;

/** Real Quartz scheduling and job service, with the two database tables held in memory. */
public class ErCombineSyncQuartzIntegrationTest {
    private static final String ORIGINAL_CRON = "0 0/5 * * * ?";
    private static final String UPDATED_CRON = "0 0/10 * * * ?";

    @Test
    public void insertingEnabledAndDisabledTasksCreatesMatchingJobRowsAndTriggers() throws Exception {
        for (String status : new String[]{"0", "1"}) {
            try (Fixture fixture = new Fixture()) {
                ErCombineSync task = task(status, ORIGINAL_CRON);

                assertEquals(1, fixture.service.insertErCombineSync(task));

                Long jobId = fixture.savedTask(task.getCombineId()).getJobId();
                assertNotNull(jobId);
                SysJob job = fixture.savedJob(jobId);
                assertEquals("合并同步-订单归集", job.getJobName());
                assertEquals("DATA_SYNC", job.getJobGroup());
                assertEquals("dataSyncTask.executeCombineSyncById(" + task.getCombineId() + "L)", job.getInvokeTarget());
                assertEquals(status, job.getStatus());
                assertEquals("1", job.getConcurrent());
                assertTrue(fixture.scheduler.getJobDetail(ScheduleUtils.getJobKey(jobId, job.getJobGroup()))
                        .getJobClass().isAnnotationPresent(DisallowConcurrentExecution.class));
                fixture.assertTrigger(jobId, ORIGINAL_CRON, "1".equals(status)
                        ? Trigger.TriggerState.PAUSED : Trigger.TriggerState.NORMAL);
                assertFalse("The test must never start execution threads", fixture.scheduler.isStarted());
            }
        }
    }

    @Test
    public void cronEditUpdatesTheSameManagedJobAndLiveTriggerAndStatusPatchesRetainCron() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ErCombineSync original = task("0", ORIGINAL_CRON);
            fixture.service.insertErCombineSync(original);
            Long jobId = fixture.savedTask(original.getCombineId()).getJobId();
            ErCombineSync cronEdit = patch(original.getCombineId());
            cronEdit.setCronExpression("  " + UPDATED_CRON + "  ");

            assertEquals(1, fixture.service.updateErCombineSync(cronEdit));

            assertEquals(jobId, fixture.savedTask(original.getCombineId()).getJobId());
            assertEquals(UPDATED_CRON, fixture.savedTask(original.getCombineId()).getCronExpression());
            assertEquals(1, fixture.jobs.size());
            assertEquals(UPDATED_CRON, fixture.savedJob(jobId).getCronExpression());
            assertEquals("合并同步-订单归集", fixture.savedJob(jobId).getJobName());
            fixture.assertTrigger(jobId, UPDATED_CRON, Trigger.TriggerState.NORMAL);

            ErCombineSync disable = patch(original.getCombineId());
            disable.setStatus("1");
            fixture.service.updateErCombineSync(disable);
            assertEquals("1", fixture.savedJob(jobId).getStatus());
            fixture.assertTrigger(jobId, UPDATED_CRON, Trigger.TriggerState.PAUSED);

            ErCombineSync enable = patch(original.getCombineId());
            enable.setStatus("0");
            fixture.service.updateErCombineSync(enable);
            assertEquals("0", fixture.savedJob(jobId).getStatus());
            fixture.assertTrigger(jobId, UPDATED_CRON, Trigger.TriggerState.NORMAL);
        }
    }

    @Test
    public void clearingCronRemovesTheActualJobGroupTriggerAndTheBusinessAssociation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ErCombineSync original = task("0", ORIGINAL_CRON);
            fixture.service.insertErCombineSync(original);
            Long jobId = fixture.savedTask(original.getCombineId()).getJobId();
            // Task management can edit a group's name independently of the business form.
            SysJob managed = fixture.jobService.selectJobById(jobId);
            managed.setJobGroup("CUSTOM_SYNC");
            fixture.jobService.updateJob(managed);
            assertTrue(fixture.scheduler.checkExists(ScheduleUtils.getJobKey(jobId, "CUSTOM_SYNC")));
            ErCombineSync clear = patch(original.getCombineId());
            clear.setCronExpression("   ");

            assertEquals(1, fixture.service.updateErCombineSync(clear));

            assertEquals("", fixture.savedTask(original.getCombineId()).getCronExpression());
            assertNull(fixture.savedTask(original.getCombineId()).getJobId());
            assertNull(fixture.savedJob(jobId));
            assertFalse(fixture.scheduler.checkExists(ScheduleUtils.getJobKey(jobId, "DATA_SYNC")));
            assertFalse(fixture.scheduler.checkExists(ScheduleUtils.getJobKey(jobId, "CUSTOM_SYNC")));
            assertNull(fixture.scheduler.getTrigger(ScheduleUtils.getTriggerKey(jobId, "CUSTOM_SYNC")));
        }
    }

    @Test
    public void addingCronRepairsMissingAndDeletedManagedJobsWithoutDuplicatingThem() throws Exception {
        for (boolean staleAssociation : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture()) {
                ErCombineSync original = task("0", null);
                fixture.service.insertErCombineSync(original);
                assertTrue(fixture.jobs.isEmpty());
                if (staleAssociation) fixture.combines.get(original.getCombineId()).setJobId(999L);
                ErCombineSync addCron = patch(original.getCombineId());
                addCron.setCronExpression(UPDATED_CRON);

                assertEquals(1, fixture.service.updateErCombineSync(addCron));

                Long jobId = fixture.savedTask(original.getCombineId()).getJobId();
                assertNotNull(jobId);
                assertNotEquals(Long.valueOf(999), jobId);
                assertEquals(1, fixture.jobs.size());
                fixture.assertTrigger(jobId, UPDATED_CRON, Trigger.TriggerState.NORMAL);
                fixture.service.updateErCombineSync(patch(original.getCombineId()));
                assertEquals(jobId, fixture.savedTask(original.getCombineId()).getJobId());
                assertEquals(1, fixture.jobs.size());
                fixture.assertTrigger(jobId, UPDATED_CRON, Trigger.TriggerState.NORMAL);
            }
        }
    }

    private static ErCombineSync task(String status, String cron) {
        ErCombineSync task = new ErCombineSync();
        task.setCombineName("订单归集");
        task.setSourceDatasourceId(1L);
        task.setTargetDatasourceId(1L);
        task.setStatus(status);
        task.setCronExpression(cron);
        task.setCreateBy("admin");
        return task;
    }

    private static ErCombineSync patch(Long combineId) {
        ErCombineSync patch = new ErCombineSync();
        patch.setCombineId(combineId);
        patch.setUpdateBy("admin");
        return patch;
    }

    private static final class Fixture implements AutoCloseable {
        final ErCombineSyncServiceImpl service = new ErCombineSyncServiceImpl();
        final SysJobServiceImpl jobService = new SysJobServiceImpl();
        final Map<Long, ErCombineSync> combines = new LinkedHashMap<>();
        final Map<Long, SysJob> jobs = new LinkedHashMap<>();
        final Scheduler scheduler;
        long nextCombineId = 21;
        long nextJobId = 101;

        Fixture() throws Exception {
            Properties properties = new Properties();
            properties.setProperty("org.quartz.scheduler.instanceName", "combine-cron-" + UUID.randomUUID());
            properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
            properties.setProperty("org.quartz.threadPool.threadCount", "1");
            properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
            properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
            properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
            scheduler = new StdSchedulerFactory(properties).getScheduler();
            inject(jobService, "scheduler", scheduler);
            inject(jobService, "jobMapper", proxy(SysJobMapper.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "selectJobById": return savedJob((Long) arguments[0]);
                    case "selectJobList":
                    case "selectJobAll":
                        ArrayList<SysJob> selected = new ArrayList<>();
                        for (SysJob job : jobs.values()) selected.add(copy(job));
                        return selected;
                    case "insertJob":
                        SysJob inserted = (SysJob) arguments[0];
                        inserted.setJobId(nextJobId++);
                        jobs.put(inserted.getJobId(), copy(inserted));
                        return 1;
                    case "updateJob":
                        SysJob changed = (SysJob) arguments[0];
                        if (!jobs.containsKey(changed.getJobId())) return 0;
                        mergeNonNull(changed, jobs.get(changed.getJobId()));
                        return 1;
                    case "deleteJobById": return jobs.remove(arguments[0]) == null ? 0 : 1;
                    default: throw new AssertionError("Unexpected job mapper method: " + method.getName());
                }
            }));
            inject(service, "jobService", jobService);
            inject(service, "erCombineSyncMapper", proxy(ErCombineSyncMapper.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "selectErCombineSyncByCombineId": return savedTask((Long) arguments[0]);
                    case "insertErCombineSync":
                        ErCombineSync inserted = (ErCombineSync) arguments[0];
                        inserted.setCombineId(nextCombineId++);
                        combines.put(inserted.getCombineId(), copy(inserted));
                        return 1;
                    case "updateErCombineSync":
                        ErCombineSync changed = (ErCombineSync) arguments[0];
                        if (!combines.containsKey(changed.getCombineId())) return 0;
                        mergeNonNull(changed, combines.get(changed.getCombineId()));
                        return 1;
                    case "updateJobId":
                        ErCombineSync linked = (ErCombineSync) arguments[0];
                        if (!combines.containsKey(linked.getCombineId())) return 0;
                        combines.get(linked.getCombineId()).setJobId(linked.getJobId());
                        return 1;
                    default: throw new AssertionError("Unexpected combine mapper method: " + method.getName());
                }
            }));
        }

        ErCombineSync savedTask(Long id) { return copy(combines.get(id)); }
        SysJob savedJob(Long id) { return copy(jobs.get(id)); }

        void assertTrigger(Long jobId, String cron, Trigger.TriggerState state) throws Exception {
            SysJob job = savedJob(jobId);
            assertNotNull(job);
            assertEquals(cron, job.getCronExpression());
            CronTrigger trigger = (CronTrigger) scheduler.getTrigger(ScheduleUtils.getTriggerKey(jobId, job.getJobGroup()));
            assertNotNull("A job row must have a live Quartz trigger", trigger);
            assertEquals(cron, trigger.getCronExpression());
            assertEquals(state, scheduler.getTriggerState(trigger.getKey()));
            assertNotNull(trigger.getNextFireTime());
        }

        @Override public void close() throws Exception { scheduler.shutdown(false); }
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T copy(T source) {
        if (source == null) return null;
        try {
            T target = (T) source.getClass().newInstance();
            BeanUtils.copyProperties(source, target);
            return target;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Simulates MyBatis' non-null dynamic updates without sharing the form object with stored rows. */
    private static void mergeNonNull(Object source, Object target) throws Exception {
        for (PropertyDescriptor property : Introspector.getBeanInfo(source.getClass()).getPropertyDescriptors()) {
            if (property.getReadMethod() == null || property.getWriteMethod() == null) continue;
            Object value = property.getReadMethod().invoke(source);
            if (value != null) property.getWriteMethod().invoke(target, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
