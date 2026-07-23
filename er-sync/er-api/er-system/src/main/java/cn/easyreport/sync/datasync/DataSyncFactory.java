package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;
/**
 * Simple factory: build DataSyncExtractor/DataSyncLoader based on datasource type.
 */
public class DataSyncFactory {

    public static DataSyncExtractor buildExtractor(ErDatasource ds) {
        String type = ds.getDatasourceType();
        if (type == null) throw new IllegalArgumentException("datasource type is null");
        switch (type.toUpperCase()) {
            case "MYSQL":
                return new MySqlDataSyncExtractor();
            case "TIDB":
                return new TiDbDataSyncExtractor();
            case "STARROCKS":
                return new StarRocksDataSyncExtractor();
            case "SQLSERVER":
                return new SqlServerDataSyncExtractor();
            case "POSTGRESQL":
                return new PostgreSqlDataSyncExtractor();
            case "ORACLE":
                return new OracleDataSyncExtractor();
            default:
                throw new UnsupportedOperationException("Unsupported extractor type: " + type);
        }
    }

    public static DataSyncLoader buildLoader(ErDatasource ds) {
        String type = ds.getDatasourceType();
        if (type == null) throw new IllegalArgumentException("datasource type is null");
        switch (type.toUpperCase()) {
            case "MYSQL":
                return new MySqlDataSyncLoader();
            case "TIDB":
                return new TiDbDataSyncLoader();
            case "STARROCKS":
                return new StarRocksDataSyncLoader();
            case "SQLSERVER":
                return new SqlServerDataSyncLoader();
            case "POSTGRESQL":
                return new PostgreSqlDataSyncLoader();
            case "ORACLE":
                return new OracleDataSyncLoader();
            default:
                throw new UnsupportedOperationException("Unsupported loader type: " + type);
        }
    }
}

