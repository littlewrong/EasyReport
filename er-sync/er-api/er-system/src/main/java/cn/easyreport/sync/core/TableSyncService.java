package cn.easyreport.sync.core;

import cn.easyreport.common.utils.DateUtils;
import cn.easyreport.sync.domain.ErDataSyncLog;
import cn.easyreport.sync.model.TableMeta;
import cn.easyreport.sync.registry.DatabaseComponentRegistry;
import cn.easyreport.sync.mapper.DatabaseTypeMapper;
import cn.easyreport.sync.model.ColumnMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 表同步服务
 * 负责单张表的同步逻辑
 */
public class TableSyncService {

    private static final Logger log = LoggerFactory.getLogger(TableSyncService.class);

    /**
     * 同步单张表
     */
    public ErDataSyncLog syncTable(TableSyncContext context) {
        ErDataSyncLog logItem = new ErDataSyncLog();
        logItem.setSyncId(context.getTask().getSyncId());
        logItem.setSourceTable(context.getSourceTableFullName());
        logItem.setTargetTable(context.getTargetTableFullName());
        logItem.setCreateTime(DateUtils.getNowDate());

        // 准备表元数据
        prepareTableMeta(context);

        // 执行类型映射
        mapColumnTypes(context);

        long start = System.currentTimeMillis();
        try {
            boolean exists = false;

            // 配置为删除重建时，无条件执行 DROP
            // 各 Strategy 已处理表不存在的情况（IF EXISTS 或 PL/SQL 异常捕获）
            if ("1".equals(context.getTask().getIfExistsAction())) {
                dropTable(context);
                exists = true; // 标记为 DROP_CREATE
            } else {
                // 配置为跳过时，检查表是否存在
                exists = context.getTargetConnector()
                    .tableExists(context.getTargetConfig().getSchema(), context.getTableName());
                if (exists) {
                    logItem.setSyncAction("SKIP");
                    logItem.setSyncResult("0");
                    logItem.setRowsAffected(0);
                    log.info("[TableSyncService] 表 {} 已存在，跳过", context.getSourceTableFullName());
                    return logItem;
                }
            }

            // 创建表
            createTable(context, logItem);

            logItem.setSyncAction(exists ? "DROP_CREATE" : "CREATE");
            logItem.setSyncResult("0");
            logItem.setRowsAffected(0);
            log.info("[TableSyncService] 表 {} 同步成功", context.getSourceTableFullName());

        } catch (Exception e) {
            log.error("[TableSyncService] 表 {} 同步失败: {}", context.getSourceTableFullName(), e.getMessage(), e);
            logItem.setSyncResult("1");
            logItem.setErrorMessage(e.getMessage());
        } finally {
            logItem.setExecuteTime((int) (System.currentTimeMillis() - start));
        }

        return logItem;
    }

    /**
     * 准备表元数据
     */
    private void prepareTableMeta(TableSyncContext context) {
        TableMeta tableMeta = context.getTableMeta();
        TargetConfig config = context.getTargetConfig();

        if (config.hasDatabase()) {
            tableMeta.setDatabase(config.getDatabase());
        }
        tableMeta.setSchema(config.getSchema());
    }

    /**
     * 执行列类型映射
     */
    private void mapColumnTypes(TableSyncContext context) {
        String sourceDbType = context.getSourceDs().getDatasourceType();
        String targetDbType = context.getTargetDs().getDatasourceType();
        TableMeta tableMeta = context.getTableMeta();

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
     * 删除表
     */
    private void dropTable(TableSyncContext context) throws Exception {
        String dropSql = context.getStrategy().buildDropTable(
            context.getTargetConfig().getDatabase(),
            context.getTargetConfig().getSchema(),
            context.getTableName()
        );
        log.debug("[TableSyncService] Executing DROP: {}", dropSql);
        context.getTargetConnector().execute(dropSql);
    }

    /**
     * 创建表
     */
    private void createTable(TableSyncContext context, ErDataSyncLog logItem) throws Exception {
        String ddl = context.getDialect().createTable(context.getTableMeta());
        log.info("[TableSyncService] Generated DDL for table {}: {}",
            context.getSourceTableFullName(),
            ddl.substring(0, Math.min(200, ddl.length())) + "...");
        log.debug("[TableSyncService] Full DDL: {}", ddl);

        context.getTargetConnector().execute(ddl);
        logItem.setExecuteSql(ddl);
    }
}
