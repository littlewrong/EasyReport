package cn.easyreport.sync.strategy;

/**
 * 数据库策略接口
 * 定义不同数据库的特定操作
 */
public interface DatabaseStrategy {

    /**
     * 获取数据库类型名称
     */
    String getDatabaseType();

    /**
     * 构建 DROP TABLE 语句
     */
    String buildDropTable(String database, String schema, String table);

    /**
     * 构建 CREATE DATABASE 语句
     */
    String buildCreateDatabase(String databaseName);

    /**
     * 构建 USE DATABASE 语句（某些数据库需要）
     */
    String buildUseDatabase(String databaseName);

    /**
     * 获取标识符引号（MySQL用`，SQL Server用[]）
     */
    String getIdentifierQuote();

    /**
     * 引用标识符（添加适当的引号）
     */
    String quoteIdentifier(String identifier);

    /**
     * 是否支持 IF EXISTS 语法
     */
    boolean supportsIfExists();
}
