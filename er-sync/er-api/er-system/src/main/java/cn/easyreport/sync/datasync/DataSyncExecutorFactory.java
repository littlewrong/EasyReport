package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mapper.ErDataTransferLogMapper;
import cn.easyreport.sync.mapper.ErDataTransferMapper;
import cn.easyreport.sync.mapper.ErDataTransferProgressMapper;

/**
 * Factory for creating database-specific data sync executors.
 */
public class DataSyncExecutorFactory {

    public static DataSyncExecutor create(
            ErDatasource targetDs,
            ErDataTransferMapper transferMapper,
            ErDataTransferLogMapper logMapper,
            ErDataTransferProgressMapper progressMapper) {

        String type = targetDs.getDatasourceType();
        if (type == null) {
            throw new IllegalArgumentException("Target datasource type is null");
        }

        switch (type.toUpperCase()) {
            case "MYSQL":
                return new MySqlDataSyncExecutor(transferMapper, logMapper, progressMapper);
            case "TIDB":
                return new TiDbDataSyncExecutor(transferMapper, logMapper, progressMapper);
            case "STARROCKS":
                return new StarRocksDataSyncExecutor(transferMapper, logMapper, progressMapper);
            case "SQLSERVER":
                return new SqlServerDataSyncExecutor(transferMapper, logMapper, progressMapper);
            case "POSTGRESQL":
                return new PostgreSqlDataSyncExecutor(transferMapper, logMapper, progressMapper);
            case "ORACLE":
                return new OracleDataSyncExecutor(transferMapper, logMapper, progressMapper);
            default:
                throw new UnsupportedOperationException("Unsupported target database type: " + type);
        }
    }
}
