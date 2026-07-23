package cn.easyreport.sync.core;

import cn.easyreport.common.utils.DateUtils;
import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDataSyncLog;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.ConnectorFactory;
import cn.easyreport.sync.DataSourceConnector;
import cn.easyreport.sync.oracle.OracleConnector;
import cn.easyreport.sync.dialect.ExtendedDialectFactory;
import cn.easyreport.sync.dialect.SchemaDialect;
import cn.easyreport.sync.extractor.ExtendedExtractorFactory;
import cn.easyreport.sync.extractor.SchemaExtractor;
import cn.easyreport.sync.mapper.DatabaseTypeMapper;
import cn.easyreport.sync.model.ColumnMeta;
import cn.easyreport.sync.model.SyncResult;
import cn.easyreport.sync.model.TableMeta;
import cn.easyreport.sync.registry.DatabaseComponentRegistry;
import cn.easyreport.sync.strategy.DatabaseStrategy;
import cn.easyreport.sync.strategy.DatabaseStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Schema sync executor using structured metadata pipeline.
 */
public class SchemaSyncExecutor {

    private static final Logger log = LoggerFactory.getLogger(SchemaSyncExecutor.class);

    /**
     * 停止检查器接口
     */
    public interface StopChecker {
        boolean shouldStop();
    }

    public SyncResult execute(ErDataSync task, ErDatasource sourceDs, ErDatasource targetDs) {
        return execute(task, sourceDs, targetDs, null, null);
    }

    public SyncResult execute(ErDataSync task, ErDatasource sourceDs, ErDatasource targetDs, SyncProgressCallback callback) {
        return execute(task, sourceDs, targetDs, callback, null);
    }

    public SyncResult execute(ErDataSync task, ErDatasource sourceDs, ErDatasource targetDs, SyncProgressCallback callback, StopChecker stopChecker) {
        SyncResult result = new SyncResult();
        result.setSuccess(true);
        result.setStopped(false);
        result.setMessage("OK");

        log.info("[SchemaSyncExecutor] 开始执行架构同步，任务ID: {}", task.getSyncId());

        try (DataSourceConnector target = ConnectorFactory.build(targetDs)) {

            String sourceSchemaPattern = task.getSourceSchemaPattern();
            String tablePattern = task.getSourceTablePattern();

            // Oracle 的 Schema 名称在数据库中存储为大写
            sourceSchemaPattern = resolveSourceSchema(sourceSchemaPattern, sourceDs.getDatasourceType());

            SchemaExtractor extractor = ExtendedExtractorFactory.build(sourceDs);
            SchemaDialect dialect = ExtendedDialectFactory.build(targetDs);
            DatabaseStrategy strategy = DatabaseStrategyFactory.getStrategy(targetDs.getDatasourceType());

            // 查询所有匹配的表（tableMeta.getSchema() 是实际库名）
            List<TableMeta> tables = extractor.listTables(sourceSchemaPattern, tablePattern);

            TableSyncService tableSyncService = new TableSyncService();
            result.setTotalCount(tables.size());
            int successCount = 0;
            int currentIndex = 0;

            // 初始化待同步表列表（用 schema.table 全限定名）
            log.info("[SchemaSyncExecutor] 找到 {} 张表，初始化待同步列表", tables.size());
            for (TableMeta tableMeta : tables) {
                String actualSchema = tableMeta.getSchema() != null ? tableMeta.getSchema() : sourceSchemaPattern;
                result.getPendingTables().add(formatName(actualSchema, tableMeta.getName()));
            }

            // 按实际 schema 分组，每个源库对应创建一个目标库（1:1 复制）
            java.util.LinkedHashMap<String, List<TableMeta>> tablesBySchema = new java.util.LinkedHashMap<>();
            for (TableMeta t : tables) {
                String actualSchema = t.getSchema() != null ? t.getSchema() : sourceSchemaPattern;
                tablesBySchema.computeIfAbsent(actualSchema, k -> new java.util.ArrayList<>()).add(t);
            }

            for (java.util.Map.Entry<String, List<TableMeta>> entry : tablesBySchema.entrySet()) {
                String actualSchema = entry.getKey();
                List<TableMeta> schemaTables = entry.getValue();

                // 为当前实际库创建目标配置和目标库
                TargetConfig schemaTargetConfig = TargetConfig.create(
                    actualSchema,
                    targetDs.getDatasourceType(),
                    target.getDatabaseName()
                );
                createDatabaseIfNeeded(targetDs, target, schemaTargetConfig);

                for (TableMeta tableMeta : schemaTables) {
                    if (stopChecker != null && stopChecker.shouldStop()) {
                        log.info("[SchemaSyncExecutor] 检测到停止标志，停止同步");
                        result.setStopped(true);
                        result.setSuccess(true);
                        result.setMessage("同步已停止（已完成 " + currentIndex + " 张表，剩余 " + (tables.size() - currentIndex) + " 张表）");
                        break;
                    }

                    currentIndex++;
                    String fullTableName = formatName(actualSchema, tableMeta.getName());
                    result.getPendingTables().remove(fullTableName);

                    log.info("[SchemaSyncExecutor] 开始同步表 [{}/{}]: {}", currentIndex, tables.size(), fullTableName);

                    TableSyncContext context = new TableSyncContext(
                        task, sourceDs, targetDs, target, dialect, strategy,
                        schemaTargetConfig, tableMeta, actualSchema
                    );

                    ErDataSyncLog logItem = tableSyncService.syncTable(context);

                    if ("0".equals(logItem.getSyncResult())) successCount++;
                    if ("1".equals(logItem.getSyncResult())) {
                        result.setSuccess(false);
                        result.setMessage("部分表同步失败");
                    }

                    result.getLogs().add(logItem);
                    result.getCompletedTables().add(fullTableName);

                    // 回调在 syncTable 之后触发，确保 N/N 时所有表已实际完成
                    if (callback != null) {
                        callback.onProgress(currentIndex, tables.size(), "已完成表: " + fullTableName);
                    }
                }

                if (result.isStopped()) break;
            }

            log.info("[SchemaSyncExecutor] 所有表同步完成，总数: {}, 成功: {}", tables.size(), successCount);
            result.setSuccessCount(successCount);

            if (tables.isEmpty()) {
                result.setSuccess(false);
                result.setMessage("未匹配到任何表");
            } else if (result.isSuccess()) {
                result.setMessage("OK");
            }

        } catch (Exception e) {
            log.error("execute sync failed", e);
            result.setSuccess(false);
            result.setMessage("同步失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 对表的列类型进行映射转换
     *
     * @param tableMeta 表元数据
     * @param sourceDbType 源数据库类型
     * @param targetDbType 目标数据库类型
     */
    private void mapColumnTypes(TableMeta tableMeta, String sourceDbType, String targetDbType) {
        if (tableMeta == null || sourceDbType == null || targetDbType == null) {
            return;
        }

        // 如果源和目标数据库类型相同，不需要映射
        if (sourceDbType.equalsIgnoreCase(targetDbType)) {
            log.debug("Source and target database types are the same ({}), skipping type mapping", sourceDbType);
            return;
        }

        // 获取源数据库的类型映射器
        DatabaseTypeMapper typeMapper = DatabaseComponentRegistry.getTypeMapper(sourceDbType);
        if (typeMapper == null) {
            log.warn("No type mapper found for source database type: {}, skipping type mapping", sourceDbType);
            return;
        }

        // 检查是否支持目标数据库
        if (!typeMapper.supportsTarget(targetDbType)) {
            log.warn("Type mapper for {} does not support target database type: {}, skipping type mapping",
                    sourceDbType, targetDbType);
            return;
        }

        // 遍历所有列，进行类型映射
        int mappedCount = 0;
        for (ColumnMeta column : tableMeta.getColumns()) {
            String originalType = column.getColumnType();
            if (originalType == null || originalType.isEmpty()) {
                continue;
            }

            // 执行类型映射
            String mappedType = typeMapper.mapType(targetDbType, originalType);

            // 如果类型发生了变化，记录日志
            if (!originalType.equals(mappedType)) {
                log.debug("Mapped column type: {}.{} {} -> {}",
                        tableMeta.getName(), column.getName(), originalType, mappedType);
                column.setColumnType(mappedType);
                mappedCount++;

                // 检查是否是有损转换
                if (typeMapper.isLossyConversion(targetDbType, originalType)) {
                    log.warn("Lossy conversion detected: {}.{} {} -> {} (data may be lost)",
                            tableMeta.getName(), column.getName(), originalType, mappedType);
                }
            }
        }

        if (mappedCount > 0) {
            log.info("Mapped {} column types for table {} from {} to {}",
                    mappedCount, tableMeta.getName(), sourceDbType, targetDbType);
        }
    }

    /**
     * Create target database/schema when missing.
     */
    private void createDatabaseIfNeeded(ErDatasource targetDs, DataSourceConnector target, TargetConfig config) {
        String databaseName = config.hasDatabase() ? config.getDatabase() : config.getSchema();
        if (databaseName == null || databaseName.isEmpty()) {
            log.debug("[createDatabaseIfNeeded] Database name is null or empty, skipping");
            return;
        }

        String dbType = targetDs.getDatasourceType();
        if (dbType == null) {
            log.debug("[createDatabaseIfNeeded] Datasource type is null, skipping");
            return;
        }

        log.info("[createDatabaseIfNeeded] Attempting to create database '{}' for database type: {}", databaseName, dbType);

        try {
            // Oracle 特殊处理：通过 OracleConnector.ensureSchemaExists() 创建 Schema(User)
            if ("ORACLE".equalsIgnoreCase(dbType) && target instanceof OracleConnector) {
                ((OracleConnector) target).ensureSchemaExists(databaseName);
                // 切换到目标 Schema
                String useSql = SqlBuilder.buildUseDatabase(dbType, databaseName);
                if (useSql != null) {
                    log.info("[createDatabaseIfNeeded] Switching to Oracle schema: {}", useSql);
                    target.execute(useSql);
                }
                return;
            }

            // 构建 CREATE DATABASE 语句
            String createSql = SqlBuilder.buildCreateDatabase(dbType, databaseName);
            if (createSql != null) {
                log.info("[createDatabaseIfNeeded] Executing SQL: {}", createSql);
                target.execute(createSql);
                log.info("[createDatabaseIfNeeded] Successfully created database: {}", databaseName);

                // 某些数据库需要切换到新创建的数据库
                String useSql = SqlBuilder.buildUseDatabase(dbType, databaseName);
                if (useSql != null) {
                    log.info("[createDatabaseIfNeeded] Switching to database: {}", useSql);
                    target.execute(useSql);
                    log.info("[createDatabaseIfNeeded] Successfully switched to database: {}", databaseName);
                }
            } else {
                log.debug("[createDatabaseIfNeeded] Database type {} does not support auto-create database", dbType);
            }
        } catch (Exception e) {
            log.error("[createDatabaseIfNeeded] Failed to create database '{}' for type {}: {}", databaseName, dbType, e.getMessage(), e);
        }
    }

    private String formatName(String schema, String table) {
        if (schema == null || schema.isEmpty()) {
            return table;
        }
        return schema + "." + table;
    }

    /**
     * 根据源数据库类型解析实际的 Schema 名称，保持源端原始大小写。
     * Oracle 的标识符在数据库中存储为大写，需要统一转为大写以匹配源端实际值。
     */
    private String resolveSourceSchema(String schema, String sourceDbType) {
        if (schema == null || schema.isEmpty()) {
            return schema;
        }
        if ("ORACLE".equalsIgnoreCase(sourceDbType)) {
            return schema.toUpperCase();
        }
        return schema;
    }
}
