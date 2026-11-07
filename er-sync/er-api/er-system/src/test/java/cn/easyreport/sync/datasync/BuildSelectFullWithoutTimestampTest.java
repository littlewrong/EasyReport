package cn.easyreport.sync.datasync;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 未配置时间戳字段时 buildSelectFull 应回退为按主键排序整表导入，
 * 而不是把 null 拼进 ORDER BY 导致 SQL 报错。
 */
public class BuildSelectFullWithoutTimestampTest {

    private static final List<String> PKS = Arrays.asList("id", "code");

    @Test public void mysqlFallsBackToPrimaryKeyOrderWithoutTimestamp() {
        MySqlDataSyncExtractor extractor = new MySqlDataSyncExtractor();
        for (String empty : new String[]{null, "", "   "}) {
            assertEquals("SELECT * FROM `db`.`t` ORDER BY `id`, `code`",
                    extractor.buildSelectFull("db", "t", empty, PKS));
            assertEquals("SELECT * FROM `t` ORDER BY `id`, `code`",
                    extractor.buildSelectFull(null, "t", empty, PKS));
        }
    }

    @Test public void mysqlKeepsTimestampOrderWhenConfigured() {
        MySqlDataSyncExtractor extractor = new MySqlDataSyncExtractor();
        assertEquals("SELECT * FROM `db`.`t` ORDER BY `ts`, `id`",
                extractor.buildSelectFull("db", "t", "ts", PKS));
    }

    @Test public void tidbFallsBackToPrimaryKeyOrderWithoutTimestamp() {
        TiDbDataSyncExtractor extractor = new TiDbDataSyncExtractor();
        for (String empty : new String[]{null, "", "   "}) {
            assertEquals("SELECT * FROM `db`.`t` ORDER BY `id`, `code`",
                    extractor.buildSelectFull("db", "t", empty, PKS));
        }
        assertEquals("SELECT * FROM `db`.`t` ORDER BY `ts`, `id`",
                extractor.buildSelectFull("db", "t", "ts", PKS));
    }

    @Test public void sqlServerFallsBackToPrimaryKeyOrderWithoutTimestamp() {
        SqlServerDataSyncExtractor extractor = new SqlServerDataSyncExtractor();
        for (String empty : new String[]{null, "", "   "}) {
            assertEquals("SELECT * FROM [db].[dbo].[t] ORDER BY [id], [code]",
                    extractor.buildSelectFull("db", "t", empty, PKS));
            assertEquals("SELECT * FROM [dbo].[t] ORDER BY [id], [code]",
                    extractor.buildSelectFull(null, "t", empty, PKS));
        }
        assertEquals("SELECT * FROM [db].[dbo].[t] ORDER BY [ts], [id]",
                extractor.buildSelectFull("db", "t", "ts", PKS));
    }

    @Test public void postgresAndOracleRegressionWithoutTimestamp() {
        PostgreSqlDataSyncExtractor pg = new PostgreSqlDataSyncExtractor();
        assertEquals("SELECT * FROM \"public\".\"t\" ORDER BY \"id\", \"code\"",
                pg.buildSelectFull(null, "t", null, PKS));
        OracleDataSyncExtractor oracle = new OracleDataSyncExtractor();
        assertEquals("SELECT * FROM \"SCHEMA1\".\"T\" ORDER BY \"id\", \"code\"",
                oracle.buildSelectFull("SCHEMA1", "t", "  ", PKS));
    }

    @Test public void noPrimaryKeyProducesNoOrderBy() {
        MySqlDataSyncExtractor extractor = new MySqlDataSyncExtractor();
        assertEquals("SELECT * FROM `t`",
                extractor.buildSelectFull(null, "t", null, Collections.emptyList()));
        SqlServerDataSyncExtractor sqlServer = new SqlServerDataSyncExtractor();
        assertEquals("SELECT * FROM [dbo].[t]",
                sqlServer.buildSelectFull(null, "t", null, Collections.emptyList()));
    }
}
