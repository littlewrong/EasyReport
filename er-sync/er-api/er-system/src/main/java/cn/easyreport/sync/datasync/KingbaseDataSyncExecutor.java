package cn.easyreport.sync.datasync;

import cn.easyreport.sync.kingbase.KingbaseJdbcSupport;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/** Kingbase JDBC transfer keeps the common batching, checkpoint and cancellation lifecycle. */
public class KingbaseDataSyncExecutor extends DataSyncExecutor {

    public KingbaseDataSyncExecutor(ErDataTransferMapper transferMapper, ErDataTransferLogMapper logMapper,
                                    ErDataTransferProgressMapper progressMapper) {
        super(transferMapper, logMapper, progressMapper, new KingbaseDataSyncValueConverter());
    }

    @Override
    protected void clearTable(Connection conn, String tableName) throws Exception {
        try (Statement statement = conn.createStatement()) {
            statement.execute("TRUNCATE TABLE " + KingbaseDataSyncLoader.tableSqlName(tableName));
        }
    }

    @Override
    protected String qualify(String schema, String table) {
        return schema == null || schema.isEmpty() ? quote(table) : quote(schema) + "." + quote(table);
    }

    @Override
    protected void executeFullInsertStatement(Connection conn, List<Object[]> rows, int start, int end,
                                              String targetTable, List<String> cols) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT INTO ")
            .append(KingbaseDataSyncLoader.tableSqlName(targetTable)).append(' ')
            .append(buildTargetColumnList(cols)).append(" OVERRIDING SYSTEM VALUE VALUES ");
        String rowTemplate = buildPlaceholderRow(cols.size());
        for (int i = start; i < end; i++) {
            if (i > start) sql.append(',');
            sql.append(rowTemplate);
        }
        try (PreparedStatement statement = conn.prepareStatement(sql.toString())) {
            bindRows(statement, rows, start, end);
            statement.executeUpdate();
        }
    }

    @Override
    protected String buildAlignDeleteSelectSql(String table, String pk, String tsField) {
        return "SELECT " + quote(pk) + ", " + quote(tsField) + " FROM " + table
            + " WHERE " + quote(tsField) + " >= ? ORDER BY " + quote(tsField) + ", " + quote(pk)
            + " FETCH FIRST ? ROWS ONLY";
    }

    private static String quote(String identifier) {
        return KingbaseJdbcSupport.quoteIdentifier(identifier);
    }
}
