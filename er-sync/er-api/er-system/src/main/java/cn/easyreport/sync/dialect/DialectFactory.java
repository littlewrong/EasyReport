package cn.easyreport.sync.dialect;

import cn.easyreport.sync.domain.ErDatasource;

public class DialectFactory {
    public static SchemaDialect build(ErDatasource ds) {
        String type = ds.getDatasourceType();
        if (type == null) {
            throw new IllegalArgumentException("datasource type is null");
        }
        switch (type.toUpperCase()) {
            case "MYSQL":
                return new MySqlDialect();
            case "TIDB":
                return new TiDbDialect();
            case "STARROCKS":
                return new StarRocksDialect();
            case "SQLSERVER":
                return new SqlServerDialect();
            case "POSTGRESQL":
                return new PostgreSqlDialect();
            case "ORACLE":
                return new OracleDialect();
            default:
                throw new UnsupportedOperationException("Unsupported dialect: " + type);
        }
    }
}
