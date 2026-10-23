package cn.easyreport.sync.mapper;

import cn.easyreport.sync.domain.ErDataTransfer;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/** Verifies atomic state predicates against the actual rendered mapper, without a database. */
public class ErDataTransferStopMapperTest
{
    private static Configuration configuration;

    @BeforeClass
    public static void loadMapper() throws Exception
    {
        configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ErDataTransfer", ErDataTransfer.class);
        String resource = "mapper/sync/ErDataTransferMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @Test
    public void startingAtomicallyExcludesRunningAndStoppingTasksOnOtherInstances()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setTransferId(7L);
        task.setSyncProgress("{}");
        String sql = sql("claimTransfer", task);
        assertTrue(sql.contains("set sync_status = '1', sync_progress = ?"));
        assertTrue(sql.contains("where transfer_id = ? and (sync_status is null or sync_status not in ('1', '5'))"));
        assertFalse(sql.contains("external_table_enabled"));
    }

    @Test
    public void remoteStopCannotOverwriteAnAlreadyCompletedRun()
    {
        Map<String, Object> parameters = stopParameters(false);
        String sql = sql("requestTransferStop", parameters);
        assertTrue(sql.contains("set sync_status = '5'"));
        assertTrue(sql.contains("where transfer_id = ? and (sync_status in ('1', '5') )"));
        assertFalse(sql.contains("or sync_status in ('0', '2', '3', '4')"));
        assertFalse(sql.contains("last_sync_time"));
    }

    @Test
    public void liveLegacyWorkerCanBeCancelledEvenWhenItWasPrematurelyMarkedStopped()
    {
        String sql = sql("requestTransferStop", stopParameters(true));
        assertTrue(sql.contains("or sync_status in ('0', '2', '3', '4')"));
        assertTrue(sql.contains("sync_status in ('1', '5')"));
    }

    @Test
    public void finishingAtomicallyGivesPersistedStopPriorityOverSuccessOrFailure()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setTransferId(7L);
        task.setSyncStatus("2");
        String sql = sql("finishTransfer", task);
        assertTrue(sql.contains("sync_status = case when sync_status in ('4', '5') or ? = '4' then '4' else ? end"));
        assertTrue(sql.contains("last_sync_result = case when sync_status in ('4', '5') or ? = '4'"));
        assertTrue(sql.contains("sync_progress = case when sync_status in ('4', '5') or ? = '4'"));
        assertFalse(sql.contains("external_table_enabled"));
    }

    @Test
    public void lateProgressCannotReplaceStoppingOrStoppedMessage()
    {
        ErDataTransfer task = new ErDataTransfer();
        task.setTransferId(7L);
        task.setSyncProgress("{}");
        String sql = sql("updateTransferProgress", task);
        assertTrue(sql.contains("where transfer_id = ? and sync_status = '1'"));
        assertFalse(sql.contains("set sync_status"));
    }

    private static Map<String, Object> stopParameters(boolean legacyActive)
    {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("transferId", 7L);
        parameters.put("syncProgress", "{}");
        parameters.put("allowLocalActive", legacyActive);
        return parameters;
    }

    private String sql(String statement, Object parameter)
    {
        BoundSql bound = configuration.getMappedStatement("cn.easyreport.sync.mapper.ErDataTransferMapper." + statement)
                .getBoundSql(parameter);
        return bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
