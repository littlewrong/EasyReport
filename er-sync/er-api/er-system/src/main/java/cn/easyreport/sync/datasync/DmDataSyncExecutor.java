package cn.easyreport.sync.datasync;

import cn.easyreport.sync.dm.DmJdbcSupport;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/** DM8 keeps the shared streaming reads, batch commits and cancellation lifecycle. */
public class DmDataSyncExecutor extends DataSyncExecutor {
    private boolean identityInsertEnabled;

    public DmDataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper,
                              ErDataTransferProgressMapper progressMapper) {
        super(transferMapper, logMapper, progressMapper, new DmDataSyncValueConverter());
    }

    @Override
    protected int getMaxFullInsertParams() {
        // DM installations may enforce a 2048 VALUES-expression limit; stay below it.
        return 2000;
    }

    @Override
    protected int getDeleteBatchSize() {
        return 1000;
    }

    @Override
    protected String normalizeTargetTableSqlName(String targetTable) {
        return DmDataSyncLoader.tableSqlName(targetTable);
    }

    @Override
    protected void clearTable(Connection conn, String tableName) throws Exception {
        try (Statement statement = conn.createStatement()) {
            statement.execute("TRUNCATE TABLE " + DmDataSyncLoader.tableSqlName(tableName));
        }
    }

    @Override
    protected void beforeBatchInsert(Connection conn, String tableName) throws Exception {
        identityInsertEnabled = false;
        String name = DmDataSyncLoader.tableSqlName(tableName);
        boolean hasIdentity = false;
        // Empty-query metadata avoids driver-specific system catalogs and LIKE name matching.
        try (Statement statement = conn.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM " + name + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                if (metadata.isAutoIncrement(column)) { hasIdentity = true; break; }
            }
        }
        if (hasIdentity) {
            try (Statement statement = conn.createStatement()) {
                statement.execute("SET IDENTITY_INSERT " + name + " ON");
                identityInsertEnabled = true;
            }
        }
    }

    @Override
    protected void afterBatchInsert(Connection conn, String tableName) throws Exception {
        if (!identityInsertEnabled) return;
        try (Statement statement = conn.createStatement()) {
            statement.execute("SET IDENTITY_INSERT " + DmDataSyncLoader.tableSqlName(tableName) + " OFF");
        } finally {
            identityInsertEnabled = false;
        }
    }

    @Override
    protected String qualify(String schema, String table) {
        return schema == null || schema.isEmpty() ? quote(table) : quote(schema) + "." + quote(table);
    }

    @Override
    protected String buildAlignDeleteSelectSql(String table, String pk, String tsField) {
        // Bind the limit in a ROWNUM predicate rather than depending on FETCH FIRST parameter support.
        return "SELECT * FROM (SELECT " + quote(pk) + ", " + quote(tsField) + " FROM " + table
            + " WHERE " + quote(tsField) + " >= ? ORDER BY " + quote(tsField) + ", " + quote(pk)
            + ") WHERE ROWNUM <= ?";
    }

    private static String quote(String identifier) {
        return DmJdbcSupport.quoteIdentifier(identifier);
    }
}
