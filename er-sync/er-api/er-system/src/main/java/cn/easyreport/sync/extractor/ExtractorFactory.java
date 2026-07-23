package cn.easyreport.sync.extractor;

import cn.easyreport.sync.domain.ErDatasource;

public class ExtractorFactory {
    public static SchemaExtractor build(ErDatasource ds) {
        String type = ds.getDatasourceType();
        if (type == null) {
            throw new IllegalArgumentException("datasource type is null");
        }
        switch (type.toUpperCase()) {
            case "MYSQL":
                return new MySqlSchemaExtractor(ds);
            case "TIDB":
                return new TiDbSchemaExtractor(ds);
            case "STARROCKS":
                return new StarRocksSchemaExtractor(ds);
            case "SQLSERVER":
                return new SqlServerSchemaExtractor(ds);
            case "POSTGRESQL":
                return new PostgreSqlSchemaExtractor(ds);
            case "ORACLE":
                return new OracleSchemaExtractor(ds);
            default:
                throw new UnsupportedOperationException("Unsupported extractor type: " + type);
        }
    }
}
