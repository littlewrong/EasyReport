package cn.easyreport.sync.core;

/**
 * 目标数据库配置
 * 封装目标数据库的 database、schema 等信息
 */
public class TargetConfig {
    private final String database;     // 数据库名称（SQL Server 等）
    private final String schema;       // Schema 名称
    private final String dbType;       // 数据库类型

    public TargetConfig(String database, String schema, String dbType) {
        this.database = database;
        this.schema = schema;
        this.dbType = dbType;
    }

    public String getDatabase() {
        return database;
    }

    public String getSchema() {
        return schema;
    }

    public String getDbType() {
        return dbType;
    }

    public boolean hasDatabase() {
        return database != null && !database.isEmpty();
    }

    public boolean isSqlServer() {
        return "SQLSERVER".equalsIgnoreCase(dbType);
    }

    /**
     * 构建目标表的完全限定名
     */
    public String buildTableName(String tableName) {
        if (hasDatabase()) {
            // SQL Server: database.schema.table
            return database + "." + schema + "." + tableName;
        } else {
            // MySQL/TiDB: schema.table
            return (schema != null && !schema.isEmpty())
                ? schema + "." + tableName
                : tableName;
        }
    }

    /**
     * 创建目标配置
     */
    public static TargetConfig create(String sourceSchema, String targetDbType, String defaultDbName) {
        // 去掉 LIKE 通配符，sourceSchema 传入的是实际库名（已由调用方解析），直接使用
        if (sourceSchema != null && sourceSchema.isEmpty()) {
            sourceSchema = null;
        }

        if (targetDbType != null && "SQLSERVER".equalsIgnoreCase(targetDbType)) {
            // SQL Server: sourceSchema 作为数据库名，Schema 固定为 dbo
            String database = (sourceSchema != null && !sourceSchema.isEmpty()) ? sourceSchema : null;
            return new TargetConfig(database, "dbo", targetDbType);
        } else if (targetDbType != null && "POSTGRESQL".equalsIgnoreCase(targetDbType)) {
            // PostgreSQL: 在当前数据库下创建 Schema（使用源 Schema 名）
            // PostgreSQL 不支持跨库操作，只在连接的数据库中创建 Schema
            String schema = (sourceSchema != null && !sourceSchema.isEmpty()) ? sourceSchema : "public";
            return new TargetConfig(null, schema, targetDbType);
        } else if (sourceSchema != null && !sourceSchema.isEmpty()) {
            // MySQL/TiDB/StarRocks：使用源 Schema
            return new TargetConfig(null, sourceSchema, targetDbType);
        } else {
            return new TargetConfig(null, defaultDbName, targetDbType);
        }
    }
}
