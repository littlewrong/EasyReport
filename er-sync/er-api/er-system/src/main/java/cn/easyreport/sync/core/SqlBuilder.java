package cn.easyreport.sync.core;

import cn.easyreport.sync.strategy.DatabaseStrategy;
import cn.easyreport.sync.strategy.DatabaseStrategyFactory;

/**
 * SQL 语句构建器
 * 根据不同数据库类型生成相应的 SQL 语句
 *
 * @deprecated 建议直接使用 DatabaseStrategy
 */
@Deprecated
public class SqlBuilder {

    /**
     * 构建 DROP TABLE 语句
     */
    public static String buildDropTable(TargetConfig config, String tableName) {
        DatabaseStrategy strategy = DatabaseStrategyFactory.getStrategy(config.getDbType());
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported database type: " + config.getDbType());
        }
        return strategy.buildDropTable(config.getDatabase(), config.getSchema(), tableName);
    }

    /**
     * 构建 CREATE DATABASE 语句
     */
    public static String buildCreateDatabase(String dbType, String databaseName) {
        DatabaseStrategy strategy = DatabaseStrategyFactory.getStrategy(dbType);
        if (strategy == null) {
            return null;
        }
        return strategy.buildCreateDatabase(databaseName);
    }

    /**
     * 构建 USE DATABASE 语句（用于 StarRocks 等）
     */
    public static String buildUseDatabase(String dbType, String databaseName) {
        DatabaseStrategy strategy = DatabaseStrategyFactory.getStrategy(dbType);
        if (strategy == null) {
            return null;
        }
        return strategy.buildUseDatabase(databaseName);
    }
}

