package cn.easyreport.sync;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.mysql.MySqlConnector;
import cn.easyreport.sync.tidb.TiDbConnector;
import cn.easyreport.sync.starrocks.StarRocksConnector;
import cn.easyreport.sync.sqlserver.SqlServerConnector;
import cn.easyreport.sync.postgresql.PostgreSqlConnector;
import cn.easyreport.sync.oracle.OracleConnector;
import cn.easyreport.sync.kingbase.KingbaseConnector;
import cn.easyreport.sync.dm.DmConnector;

/**
 * Simple factory that builds connector by datasource type.
 */
public class ConnectorFactory {

    public static DataSourceConnector build(ErDatasource ds) throws Exception {
        String type = ds.getDatasourceType();
        if (type == null) {
            throw new IllegalArgumentException("datasource type is null");
        }
        switch (type.toUpperCase()) {
            case "MYSQL":
                return new MySqlConnector(ds);
            case "TIDB":
                return new TiDbConnector(ds);
            case "STARROCKS":
                return new StarRocksConnector(ds);
            case "SQLSERVER":
                return new SqlServerConnector(ds);
            case "POSTGRESQL":
                return new PostgreSqlConnector(ds);
            case "ORACLE":
                return new OracleConnector(ds);
            case "KINGBASE":
                return new KingbaseConnector(ds);
            case "DM":
                return new DmConnector(ds);
            default:
                throw new UnsupportedOperationException("Unsupported datasource type: " + type);
        }
    }
}
