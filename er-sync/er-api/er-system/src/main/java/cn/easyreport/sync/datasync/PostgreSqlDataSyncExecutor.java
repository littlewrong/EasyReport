package cn.easyreport.sync.datasync;

import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;

import java.sql.Connection;
import java.sql.Statement;

/**
 * PostgreSQL数据同步执行器
 */
public class PostgreSqlDataSyncExecutor extends DataSyncExecutor {

    public PostgreSqlDataSyncExecutor(ErDataTransferMapper transferMapper,
                                      ErDataTransferLogMapper logMapper,
                                      ErDataTransferProgressMapper progressMapper) {
        super(transferMapper, logMapper, progressMapper, new DefaultDataSyncValueConverter(true));
    }

    @Override
    protected void clearTable(Connection conn, String tableName) throws Exception {
        // PostgreSQL 使用 TRUNCATE TABLE 清空表
        // tableName 可能已经是完全限定名格式：schema.table 或 "schema"."table"
        try (Statement st = conn.createStatement()) {
            // 如果已经包含双引号，直接使用；否则添加双引号
            String sql = tableName.contains("\"")
                ? "TRUNCATE TABLE " + tableName
                : "TRUNCATE TABLE \"" + tableName + "\"";
            st.execute(sql);
        }
    }

    @Override
    protected String qualify(String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return table;
        }
        // PostgreSQL: "schema"."table"
        return "\"" + schema + "\".\"" + table + "\"";
    }
}
