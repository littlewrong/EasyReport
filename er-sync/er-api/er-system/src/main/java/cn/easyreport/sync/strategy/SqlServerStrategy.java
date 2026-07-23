package cn.easyreport.sync.strategy;

/**
 * SQL Server 数据库策略
 */
public class SqlServerStrategy implements DatabaseStrategy {

    @Override
    public String getDatabaseType() {
        return "SQLSERVER";
    }

    @Override
    public String buildDropTable(String database, String schema, String table) {
        if (database != null && !database.isEmpty()) {
            return String.format("DROP TABLE IF EXISTS [%s].[%s].[%s]", database, schema, table);
        } else {
            String s = (schema != null && !schema.isEmpty()) ? schema : "dbo";
            return String.format("DROP TABLE IF EXISTS [%s].[%s]", s, table);
        }
    }

    @Override
    public String buildCreateDatabase(String databaseName) {
        return String.format(
            "IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = N'%s') CREATE DATABASE [%s]",
            databaseName, databaseName
        );
    }

    @Override
    public String buildUseDatabase(String databaseName) {
        // SQL Server 不需要显式 USE（连接字符串中指定）
        return null;
    }

    @Override
    public String getIdentifierQuote() {
        return "[]";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier + "]";
    }

    @Override
    public boolean supportsIfExists() {
        return true;
    }
}
