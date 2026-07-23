package cn.easyreport.sync.strategy;

/**
 * Oracle 数据库策略
 *
 * Oracle 与其他数据库的主要差异：
 * 1. 使用双引号 " 来引用标识符
 * 2. Schema 等同于 User，对应 MySQL 的 Database
 * 3. 完全限定名格式："SCHEMA"."TABLE"
 * 4. 不支持 CREATE DATABASE，通过 CREATE USER 创建 Schema
 * 5. 标识符默认大写
 */
public class OracleStrategy implements DatabaseStrategy {

    @Override
    public String getDatabaseType() {
        return "ORACLE";
    }

    @Override
    public String buildDropTable(String database, String schema, String table) {
        // Oracle 不支持 DROP TABLE IF EXISTS，使用 PL/SQL 匿名块包装
        // ORA-00942 表示表不存在，捕获后忽略
        String fullName;
        if (schema != null && !schema.isEmpty()) {
            fullName = String.format("\"%s\".\"%s\"", schema.toUpperCase(), table.toUpperCase());
        } else {
            fullName = String.format("\"%s\"", table.toUpperCase());
        }
        return String.format(
            "BEGIN EXECUTE IMMEDIATE 'DROP TABLE %s'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;",
            fullName
        );
    }

    @Override
    public String buildCreateDatabase(String schemaName) {
        // Oracle 不支持 CREATE DATABASE，创建 Schema = 创建 User
        // 由 SchemaSyncExecutor.createDatabaseIfNeeded() 中特殊处理
        return null;
    }

    @Override
    public String buildUseDatabase(String databaseName) {
        // Oracle 不支持 USE 语句，通过 ALTER SESSION SET CURRENT_SCHEMA 切换
        return "ALTER SESSION SET CURRENT_SCHEMA = \"" + databaseName.toUpperCase() + "\"";
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
        return false;
    }
}
