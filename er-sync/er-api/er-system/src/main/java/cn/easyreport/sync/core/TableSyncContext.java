package cn.easyreport.sync.core;

import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.DataSourceConnector;
import cn.easyreport.sync.dialect.SchemaDialect;
import cn.easyreport.sync.model.TableMeta;
import cn.easyreport.sync.strategy.DatabaseStrategy;

/**
 * 表同步上下文
 * 封装单张表同步所需的所有信息
 */
public class TableSyncContext {
    private final ErDataSync task;
    private final ErDatasource sourceDs;
    private final ErDatasource targetDs;
    private final DataSourceConnector targetConnector;
    private final SchemaDialect dialect;
    private final DatabaseStrategy strategy;
    private final TargetConfig targetConfig;
    private final TableMeta tableMeta;
    private final String sourceSchema;

    public TableSyncContext(ErDataSync task,
                            ErDatasource sourceDs,
                            ErDatasource targetDs,
                            DataSourceConnector targetConnector,
                            SchemaDialect dialect,
                            DatabaseStrategy strategy,
                            TargetConfig targetConfig,
                            TableMeta tableMeta,
                            String sourceSchema) {
        this.task = task;
        this.sourceDs = sourceDs;
        this.targetDs = targetDs;
        this.targetConnector = targetConnector;
        this.dialect = dialect;
        this.strategy = strategy;
        this.targetConfig = targetConfig;
        this.tableMeta = tableMeta;
        this.sourceSchema = sourceSchema;
    }

    public ErDataSync getTask() {
        return task;
    }

    public ErDatasource getSourceDs() {
        return sourceDs;
    }

    public ErDatasource getTargetDs() {
        return targetDs;
    }

    public DataSourceConnector getTargetConnector() {
        return targetConnector;
    }

    public SchemaDialect getDialect() {
        return dialect;
    }

    public DatabaseStrategy getStrategy() {
        return strategy;
    }

    public TargetConfig getTargetConfig() {
        return targetConfig;
    }

    public TableMeta getTableMeta() {
        return tableMeta;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public String getTableName() {
        return tableMeta.getName();
    }

    public String getSourceTableFullName() {
        return formatName(sourceSchema, tableMeta.getName());
    }

    public String getTargetTableFullName() {
        return targetConfig.buildTableName(tableMeta.getName());
    }

    private String formatName(String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return table;
        }
        return schema + "." + table;
    }
}
