package cn.easyreport.sync.strategy;

/**
 * PostgreSQL 数据库策略
 *
 * PostgreSQL 与 MySQL/SQL Server 的主要差异：
 * 1. 使用双引号 " 来引用标识符（不是反引号或方括号）
 * 2. Schema 是标准的 SQL 概念，类似 SQL Server
 * 3. 完全限定名格式：schema.table（不跨库操作）
 * 4. 不支持跨数据库操作，只能在当前连接的数据库中创建 Schema
 * 5. 默认 Schema 是 public
 */
public class PostgreSqlStrategy implements DatabaseStrategy {

    @Override
    public String getDatabaseType() {
        return "POSTGRESQL";
    }

    @Override
    public String buildDropTable(String database, String schema, String table) {
        // PostgreSQL 使用双引号引用标识符
        if (schema != null && !schema.isEmpty()) {
            return String.format("DROP TABLE IF EXISTS \"%s\".\"%s\"", schema, table);
        } else {
            return String.format("DROP TABLE IF EXISTS \"%s\"", table);
        }
    }

    @Override
    public String buildCreateDatabase(String schemaName) {
        // PostgreSQL 不跨库操作，在当前数据库中创建 Schema
        // 注意：参数名虽然是 databaseName，但对于 PostgreSQL 实际是 Schema 名称
        return String.format("CREATE SCHEMA IF NOT EXISTS \"%s\"", schemaName);
    }

    @Override
    public String buildUseDatabase(String databaseName) {
        // PostgreSQL 不支持 USE 语句，需要重新连接
        // 通常在连接字符串中指定数据库
        return null;
    }

    @Override
    public String getIdentifierQuote() {
        return "\"";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public boolean supportsIfExists() {
        return true;
    }
}
