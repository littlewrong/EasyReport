package cn.easyreport.sync.strategy;

/**
 * MySQL 数据库策略
 */
public class MySqlStrategy implements DatabaseStrategy {

    @Override
    public String getDatabaseType() {
        return "MYSQL";
    }

    @Override
    public String buildDropTable(String database, String schema, String table) {
        String schemaName = (schema != null && !schema.isEmpty()) ? schema : database;
        if (schemaName != null && !schemaName.isEmpty()) {
            return String.format("DROP TABLE IF EXISTS `%s`.`%s`", schemaName, table);
        } else {
            return String.format("DROP TABLE IF EXISTS `%s`", table);
        }
    }

    @Override
    public String buildCreateDatabase(String databaseName) {
        return String.format("CREATE DATABASE IF NOT EXISTS `%s`", databaseName);
    }

    @Override
    public String buildUseDatabase(String databaseName) {
        // MySQL 不需要显式 USE（连接字符串中指定）
        return null;
    }

    @Override
    public String getIdentifierQuote() {
        return "`";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public boolean supportsIfExists() {
        return true;
    }
}
