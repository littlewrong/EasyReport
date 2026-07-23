package cn.easyreport.sync.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * PostgreSQL 类型映射器
 *
 * 负责将 PostgreSQL 数据类型映射到其他数据库系统
 */
public class PostgreSqlTypeMapper implements DatabaseTypeMapper {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlTypeMapper.class);

    private static final String SOURCE_DB_TYPE = "POSTGRESQL";

    private final Map<String, List<TypeMappingRule>> mappingRules = new HashMap<>();

    public PostgreSqlTypeMapper() {
        initializePostgreSqlMappings();
        initializeMySqlMappings();
        initializeTiDbMappings();
        initializeStarRocksMappings();
        initializeSqlServerMappings();
        initializeOracleMappings();
    }

    @Override
    public String getSourceDatabaseType() {
        return SOURCE_DB_TYPE;
    }

    @Override
    public String mapType(String targetDbType, String sourceType) {
        if (targetDbType == null || sourceType == null) {
            return sourceType;
        }

        String targetKey = targetDbType.toUpperCase();
        List<TypeMappingRule> rules = mappingRules.get(targetKey);

        if (rules == null || rules.isEmpty()) {
            log.debug("No mapping rules found for target database: {}", targetDbType);
            return sourceType;
        }

        for (TypeMappingRule rule : rules) {
            if (rule.matches(sourceType)) {
                String result = rule.apply(sourceType);
                log.debug("Mapped PostgreSQL type '{}' to {} type '{}' using rule: {}",
                        sourceType, targetDbType, result, rule.getSourcePattern());
                return result;
            }
        }

        log.debug("No matching rule for PostgreSQL type '{}' to {}, keeping original", sourceType, targetDbType);
        return sourceType;
    }

    @Override
    public boolean supportsTarget(String targetDbType) {
        return targetDbType != null && mappingRules.containsKey(targetDbType.toUpperCase());
    }

    @Override
    public boolean isLossyConversion(String targetDbType, String sourceType) {
        if (targetDbType == null || sourceType == null) {
            return false;
        }

        String targetKey = targetDbType.toUpperCase();
        List<TypeMappingRule> rules = mappingRules.get(targetKey);

        if (rules == null) {
            return false;
        }

        for (TypeMappingRule rule : rules) {
            if (rule.matches(sourceType)) {
                return rule.isLossy();
            }
        }

        return false;
    }

    @Override
    public List<String> getSupportedTargets() {
        return new ArrayList<>(mappingRules.keySet());
    }

    private void initializePostgreSqlMappings() {
        List<TypeMappingRule> pgRules = new ArrayList<>();
        pgRules.add(new TypeMappingRule(".*", "$0", 999, false, "PostgreSQL同源映射"));
        mappingRules.put("POSTGRESQL", pgRules);
        log.info("Initialized {} PostgreSQL->PostgreSQL type mapping rules", pgRules.size());
    }

    private void initializeMySqlMappings() {
        List<TypeMappingRule> mysqlRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        mysqlRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 10, false, "SMALLINT 兼容"));
        mysqlRules.add(new TypeMappingRule("INTEGER", "INT", 11, false, "INTEGER -> INT"));
        mysqlRules.add(new TypeMappingRule("BIGINT", "BIGINT", 12, false, "BIGINT 兼容"));
        mysqlRules.add(new TypeMappingRule("SMALLSERIAL", "SMALLINT AUTO_INCREMENT", 13, false, "SMALLSERIAL -> SMALLINT AUTO_INCREMENT"));
        mysqlRules.add(new TypeMappingRule("SERIAL", "INT AUTO_INCREMENT", 14, false, "SERIAL -> INT AUTO_INCREMENT"));
        mysqlRules.add(new TypeMappingRule("BIGSERIAL", "BIGINT AUTO_INCREMENT", 15, false, "BIGSERIAL -> BIGINT AUTO_INCREMENT"));

        // ==================== 浮点类型 ====================
        mysqlRules.add(new TypeMappingRule("REAL", "FLOAT", 20, false, "REAL -> FLOAT"));
        mysqlRules.add(new TypeMappingRule("DOUBLE PRECISION", "DOUBLE", 21, false, "DOUBLE PRECISION 兼容"));

        // ==================== 精确数值类型 ====================
        mysqlRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false, "DECIMAL 兼容"));
        mysqlRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 31, false, "NUMERIC -> DECIMAL"));

        // ==================== 字符串类型 ====================
        mysqlRules.add(new TypeMappingRule("CHARACTER VARYING\\((\\d+)\\)", "VARCHAR($1)", 40, false, "CHARACTER VARYING -> VARCHAR"));
        mysqlRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 41, false, "VARCHAR 兼容"));
        mysqlRules.add(new TypeMappingRule("CHARACTER VARYING", "TEXT", 42, false, "CHARACTER VARYING (无长度) -> TEXT"));
        mysqlRules.add(new TypeMappingRule("VARCHAR", "TEXT", 43, false, "VARCHAR (无长度) -> TEXT"));
        mysqlRules.add(new TypeMappingRule("CHARACTER\\((\\d+)\\)", "CHAR($1)", 44, false, "CHARACTER -> CHAR"));
        mysqlRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 45, false, "CHAR 兼容"));
        mysqlRules.add(new TypeMappingRule("TEXT", "TEXT", 46, false, "TEXT 兼容"));

        // ==================== 日期时间类型 ====================
        mysqlRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        mysqlRules.add(new TypeMappingRule("TIME WITHOUT TIME ZONE", "TIME", 51, false, "TIME WITHOUT TIME ZONE -> TIME"));
        mysqlRules.add(new TypeMappingRule("TIME WITH TIME ZONE", "TIME", 52, true, "TIME WITH TIME ZONE -> TIME (丢失时区)"));
        mysqlRules.add(new TypeMappingRule("TIME", "TIME", 53, false, "TIME 兼容"));
        mysqlRules.add(new TypeMappingRule("TIMESTAMP WITHOUT TIME ZONE", "DATETIME", 54, false, "TIMESTAMP WITHOUT TIME ZONE -> DATETIME"));
        mysqlRules.add(new TypeMappingRule("TIMESTAMP WITH TIME ZONE", "DATETIME", 55, true, "TIMESTAMPTZ -> DATETIME (丢失时区)"));
        mysqlRules.add(new TypeMappingRule("TIMESTAMP", "DATETIME", 56, false, "TIMESTAMP -> DATETIME"));

        // ==================== 二进制类型 ====================
        mysqlRules.add(new TypeMappingRule("BYTEA", "LONGBLOB", 60, false, "BYTEA -> LONGBLOB"));

        // ==================== 其他类型 ====================
        mysqlRules.add(new TypeMappingRule("BOOLEAN", "TINYINT(1)", 70, false, "BOOLEAN -> TINYINT(1)"));
        mysqlRules.add(new TypeMappingRule("UUID", "CHAR(36)", 71, false, "UUID -> CHAR(36)"));
        mysqlRules.add(new TypeMappingRule("JSON", "JSON", 72, false, "JSON 兼容"));
        mysqlRules.add(new TypeMappingRule("JSONB", "JSON", 73, false, "JSONB -> JSON"));

        // ==================== 通配符兜底规则 ====================
        mysqlRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        mysqlRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("MYSQL", mysqlRules);
        log.info("Initialized {} PostgreSQL->MySQL type mapping rules", mysqlRules.size());
    }

    private void initializeTiDbMappings() {
        List<TypeMappingRule> tidbRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        tidbRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 10, false, "SMALLINT 兼容"));
        tidbRules.add(new TypeMappingRule("INTEGER", "INT", 11, false, "INTEGER -> INT"));
        tidbRules.add(new TypeMappingRule("BIGINT", "BIGINT", 12, false, "BIGINT 兼容"));
        tidbRules.add(new TypeMappingRule("SMALLSERIAL", "SMALLINT AUTO_INCREMENT", 13, false, "SMALLSERIAL -> SMALLINT AUTO_INCREMENT"));
        tidbRules.add(new TypeMappingRule("SERIAL", "INT AUTO_INCREMENT", 14, false, "SERIAL -> INT AUTO_INCREMENT"));
        tidbRules.add(new TypeMappingRule("BIGSERIAL", "BIGINT AUTO_INCREMENT", 15, false, "BIGSERIAL -> BIGINT AUTO_INCREMENT"));

        // ==================== 浮点类型 ====================
        tidbRules.add(new TypeMappingRule("REAL", "FLOAT", 20, false, "REAL -> FLOAT"));
        tidbRules.add(new TypeMappingRule("DOUBLE PRECISION", "DOUBLE", 21, false, "DOUBLE PRECISION -> DOUBLE"));

        // ==================== 精确数值类型 ====================
        tidbRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false, "DECIMAL 兼容"));
        tidbRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 31, false, "NUMERIC -> DECIMAL"));

        // ==================== 字符串类型 ====================
        tidbRules.add(new TypeMappingRule("CHARACTER VARYING\\((\\d+)\\)", "VARCHAR($1)", 40, false, "CHARACTER VARYING -> VARCHAR"));
        tidbRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 41, false, "VARCHAR 兼容"));
        tidbRules.add(new TypeMappingRule("CHARACTER VARYING", "TEXT", 42, false, "CHARACTER VARYING (无长度) -> TEXT"));
        tidbRules.add(new TypeMappingRule("VARCHAR", "TEXT", 43, false, "VARCHAR (无长度) -> TEXT"));
        tidbRules.add(new TypeMappingRule("CHARACTER\\((\\d+)\\)", "CHAR($1)", 44, false, "CHARACTER -> CHAR"));
        tidbRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 45, false, "CHAR 兼容"));
        tidbRules.add(new TypeMappingRule("TEXT", "TEXT", 46, false, "TEXT 兼容"));

        // ==================== 日期时间类型 ====================
        tidbRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        tidbRules.add(new TypeMappingRule("TIME WITHOUT TIME ZONE", "TIME", 51, false, "TIME WITHOUT TIME ZONE -> TIME"));
        tidbRules.add(new TypeMappingRule("TIME WITH TIME ZONE", "TIME", 52, true, "TIME WITH TIME ZONE -> TIME (丢失时区)"));
        tidbRules.add(new TypeMappingRule("TIME", "TIME", 53, false, "TIME 兼容"));
        tidbRules.add(new TypeMappingRule("TIMESTAMP WITHOUT TIME ZONE", "DATETIME", 54, false, "TIMESTAMP WITHOUT TIME ZONE -> DATETIME"));
        tidbRules.add(new TypeMappingRule("TIMESTAMP WITH TIME ZONE", "DATETIME", 55, true, "TIMESTAMPTZ -> DATETIME (丢失时区)"));
        tidbRules.add(new TypeMappingRule("TIMESTAMP", "DATETIME", 56, false, "TIMESTAMP -> DATETIME"));

        // ==================== 二进制类型 ====================
        tidbRules.add(new TypeMappingRule("BYTEA", "LONGBLOB", 60, false, "BYTEA -> LONGBLOB"));

        // ==================== 其他类型 ====================
        tidbRules.add(new TypeMappingRule("BOOLEAN", "TINYINT(1)", 70, false, "BOOLEAN -> TINYINT(1)"));
        tidbRules.add(new TypeMappingRule("UUID", "CHAR(36)", 71, false, "UUID -> CHAR(36)"));
        tidbRules.add(new TypeMappingRule("JSON", "JSON", 72, false, "JSON 兼容"));
        tidbRules.add(new TypeMappingRule("JSONB", "JSON", 73, false, "JSONB -> JSON"));

        // ==================== 通配符兜底规则 ====================
        tidbRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        tidbRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("TIDB", tidbRules);
        log.info("Initialized {} PostgreSQL->TiDB type mapping rules", tidbRules.size());
    }

    private void initializeStarRocksMappings() {
        List<TypeMappingRule> starrocksRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        starrocksRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 10, false, "SMALLINT 兼容"));
        starrocksRules.add(new TypeMappingRule("INTEGER", "INT", 11, false, "INTEGER -> INT"));
        starrocksRules.add(new TypeMappingRule("BIGINT", "BIGINT", 12, false, "BIGINT 兼容"));
        starrocksRules.add(new TypeMappingRule("SMALLSERIAL", "SMALLINT", 13, false, "SMALLSERIAL -> SMALLINT"));
        starrocksRules.add(new TypeMappingRule("SERIAL", "INT", 14, false, "SERIAL -> INT"));
        starrocksRules.add(new TypeMappingRule("BIGSERIAL", "BIGINT", 15, false, "BIGSERIAL -> BIGINT"));

        // ==================== 浮点类型 ====================
        starrocksRules.add(new TypeMappingRule("REAL", "FLOAT", 20, false, "REAL -> FLOAT"));
        starrocksRules.add(new TypeMappingRule("DOUBLE PRECISION", "DOUBLE", 21, false, "DOUBLE PRECISION -> DOUBLE"));

        // ==================== 精确数值类型 ====================
        starrocksRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false, "DECIMAL 兼容"));
        starrocksRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 31, false, "NUMERIC -> DECIMAL"));

        // ==================== 字符串类型 ====================
        starrocksRules.add(new TypeMappingRule("CHARACTER VARYING\\((\\d+)\\)", "STRING", 40, false, "CHARACTER VARYING -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "STRING", 41, false, "VARCHAR -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("CHARACTER VARYING", "STRING", 42, false, "CHARACTER VARYING (无长度) -> STRING"));
        starrocksRules.add(new TypeMappingRule("VARCHAR", "STRING", 43, false, "VARCHAR (无长度) -> STRING"));
        starrocksRules.add(new TypeMappingRule("CHARACTER\\((\\d+)\\)", "STRING", 44, false, "CHARACTER -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "STRING", 45, false, "CHAR -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("TEXT", "STRING", 46, false, "TEXT -> STRING"));

        // ==================== 日期时间类型 ====================
        starrocksRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        starrocksRules.add(new TypeMappingRule("TIME WITHOUT TIME ZONE", "VARCHAR(20)", 51, true, "TIME WITHOUT TIME ZONE -> VARCHAR(20)"));
        starrocksRules.add(new TypeMappingRule("TIME WITH TIME ZONE", "VARCHAR(20)", 52, true, "TIME WITH TIME ZONE -> VARCHAR(20)"));
        starrocksRules.add(new TypeMappingRule("TIME", "VARCHAR(20)", 53, true, "TIME -> VARCHAR(20)"));
        starrocksRules.add(new TypeMappingRule("TIMESTAMP WITHOUT TIME ZONE", "DATETIME", 54, false, "TIMESTAMP WITHOUT TIME ZONE -> DATETIME"));
        starrocksRules.add(new TypeMappingRule("TIMESTAMP WITH TIME ZONE", "DATETIME", 55, true, "TIMESTAMPTZ -> DATETIME (丢失时区)"));
        starrocksRules.add(new TypeMappingRule("TIMESTAMP", "DATETIME", 56, false, "TIMESTAMP -> DATETIME"));

        // ==================== 二进制类型 ====================
        starrocksRules.add(new TypeMappingRule("BYTEA", "VARBINARY", 60, false, "BYTEA -> VARBINARY"));

        // ==================== 其他类型 ====================
        starrocksRules.add(new TypeMappingRule("BOOLEAN", "BOOLEAN", 70, false, "BOOLEAN 兼容"));
        starrocksRules.add(new TypeMappingRule("UUID", "VARCHAR(36)", 71, false, "UUID -> VARCHAR(36)"));
        starrocksRules.add(new TypeMappingRule("JSON", "JSON", 72, false, "JSON 兼容"));
        starrocksRules.add(new TypeMappingRule("JSONB", "JSON", 73, false, "JSONB -> JSON"));

        // ==================== 通配符兜底规则 ====================
        starrocksRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        starrocksRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("STARROCKS", starrocksRules);
        log.info("Initialized {} PostgreSQL->StarRocks type mapping rules", starrocksRules.size());
    }

    private void initializeSqlServerMappings() {
        List<TypeMappingRule> sqlserverRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        sqlserverRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 10, false, "SMALLINT 兼容"));
        sqlserverRules.add(new TypeMappingRule("INTEGER", "INT", 11, false, "INTEGER -> INT"));
        sqlserverRules.add(new TypeMappingRule("BIGINT", "BIGINT", 12, false, "BIGINT 兼容"));
        sqlserverRules.add(new TypeMappingRule("SMALLSERIAL", "SMALLINT IDENTITY", 13, false, "SMALLSERIAL -> SMALLINT IDENTITY"));
        sqlserverRules.add(new TypeMappingRule("SERIAL", "INT IDENTITY", 14, false, "SERIAL -> INT IDENTITY"));
        sqlserverRules.add(new TypeMappingRule("BIGSERIAL", "BIGINT IDENTITY", 15, false, "BIGSERIAL -> BIGINT IDENTITY"));

        // ==================== 浮点类型 ====================
        sqlserverRules.add(new TypeMappingRule("REAL", "REAL", 20, false, "REAL 兼容"));
        sqlserverRules.add(new TypeMappingRule("DOUBLE PRECISION", "FLOAT", 21, false, "DOUBLE PRECISION -> FLOAT"));

        // ==================== 精确数值类型 ====================
        sqlserverRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false, "DECIMAL 兼容"));
        sqlserverRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "NUMERIC($1,$2)", 31, false, "NUMERIC 兼容"));

        // ==================== 字符串类型（使用 N 前缀支持 Unicode）====================
        sqlserverRules.add(new TypeMappingRule("CHARACTER VARYING\\((\\d+)\\)", "NVARCHAR($1)", 40, false, "CHARACTER VARYING -> NVARCHAR"));
        sqlserverRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "NVARCHAR($1)", 41, false, "VARCHAR -> NVARCHAR (Unicode 兼容)"));
        sqlserverRules.add(new TypeMappingRule("CHARACTER VARYING", "NVARCHAR(MAX)", 42, false, "CHARACTER VARYING (无长度) -> NVARCHAR(MAX)"));
        sqlserverRules.add(new TypeMappingRule("VARCHAR", "NVARCHAR(MAX)", 43, false, "VARCHAR (无长度) -> NVARCHAR(MAX)"));
        sqlserverRules.add(new TypeMappingRule("CHARACTER\\((\\d+)\\)", "NCHAR($1)", 44, false, "CHARACTER -> NCHAR (Unicode 兼容)"));
        sqlserverRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "NCHAR($1)", 45, false, "CHAR -> NCHAR (Unicode 兼容)"));
        sqlserverRules.add(new TypeMappingRule("TEXT", "NVARCHAR(MAX)", 46, false, "TEXT -> NVARCHAR(MAX)"));

        // ==================== 日期时间类型 ====================
        sqlserverRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        sqlserverRules.add(new TypeMappingRule("TIME WITHOUT TIME ZONE", "TIME", 51, false, "TIME WITHOUT TIME ZONE -> TIME"));
        sqlserverRules.add(new TypeMappingRule("TIME WITH TIME ZONE", "TIME", 52, true, "TIME WITH TIME ZONE -> TIME (丢失时区)"));
        sqlserverRules.add(new TypeMappingRule("TIME", "TIME", 53, false, "TIME 兼容"));
        sqlserverRules.add(new TypeMappingRule("TIMESTAMP WITHOUT TIME ZONE", "DATETIME2", 54, false, "TIMESTAMP WITHOUT TIME ZONE -> DATETIME2"));
        sqlserverRules.add(new TypeMappingRule("TIMESTAMP WITH TIME ZONE", "DATETIMEOFFSET", 55, false, "TIMESTAMPTZ -> DATETIMEOFFSET"));
        sqlserverRules.add(new TypeMappingRule("TIMESTAMP", "DATETIME2", 56, false, "TIMESTAMP -> DATETIME2"));

        // ==================== 二进制类型 ====================
        sqlserverRules.add(new TypeMappingRule("BYTEA", "VARBINARY(MAX)", 60, false, "BYTEA -> VARBINARY(MAX)"));

        // ==================== 其他类型 ====================
        sqlserverRules.add(new TypeMappingRule("BOOLEAN", "BIT", 70, false, "BOOLEAN -> BIT"));
        sqlserverRules.add(new TypeMappingRule("UUID", "UNIQUEIDENTIFIER", 71, false, "UUID -> UNIQUEIDENTIFIER"));
        sqlserverRules.add(new TypeMappingRule("JSON", "NVARCHAR(MAX)", 72, false, "JSON -> NVARCHAR(MAX)"));
        sqlserverRules.add(new TypeMappingRule("JSONB", "NVARCHAR(MAX)", 73, false, "JSONB -> NVARCHAR(MAX)"));

        // ==================== 通配符兜底规则 ====================
        sqlserverRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        sqlserverRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("SQLSERVER", sqlserverRules);
        log.info("Initialized {} PostgreSQL->SQL Server type mapping rules", sqlserverRules.size());
    }

    /**
     * 初始化 PostgreSQL -> Oracle 的类型映射规则
     */
    private void initializeOracleMappings() {
        List<TypeMappingRule> oracleRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        oracleRules.add(new TypeMappingRule("SMALLINT", "NUMBER(5)", 10, false, "SMALLINT -> NUMBER(5)"));
        oracleRules.add(new TypeMappingRule("INTEGER", "NUMBER(10)", 11, false, "INTEGER -> NUMBER(10)"));
        oracleRules.add(new TypeMappingRule("BIGINT", "NUMBER(19)", 12, false, "BIGINT -> NUMBER(19)"));
        oracleRules.add(new TypeMappingRule("SMALLSERIAL", "NUMBER(5)", 13, false, "SMALLSERIAL -> NUMBER(5)"));
        oracleRules.add(new TypeMappingRule("SERIAL", "NUMBER(10)", 14, false, "SERIAL -> NUMBER(10)"));
        oracleRules.add(new TypeMappingRule("BIGSERIAL", "NUMBER(19)", 15, false, "BIGSERIAL -> NUMBER(19)"));

        // ==================== 浮点类型 ====================
        oracleRules.add(new TypeMappingRule("REAL", "BINARY_FLOAT", 20, false, "REAL -> BINARY_FLOAT"));
        oracleRules.add(new TypeMappingRule("DOUBLE PRECISION", "BINARY_DOUBLE", 21, false, "DOUBLE PRECISION -> BINARY_DOUBLE"));

        // ==================== 精确数值类型 ====================
        oracleRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "NUMBER($1,$2)", 30, false, "DECIMAL -> NUMBER(p,s)"));
        oracleRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "NUMBER($1,$2)", 31, false, "NUMERIC -> NUMBER(p,s)"));

        // ==================== 字符串类型 ====================
        // Oracle NVARCHAR2 最大 4000，NCHAR 最大 2000；PostgreSQL 长度超出时降级为 NCLOB/NCHAR(2000)
        oracleRules.add(new TypeMappingRule("CHARACTER VARYING\\((\\d+)\\)", "NVARCHAR2($1)", 40, false, "CHARACTER VARYING -> NVARCHAR2 (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "NVARCHAR2($1)", 41, false, "VARCHAR -> NVARCHAR2 (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("CHARACTER VARYING", "NCLOB", 42, false, "CHARACTER VARYING (无长度) -> NCLOB"));
        oracleRules.add(new TypeMappingRule("VARCHAR", "NCLOB", 43, false, "VARCHAR (无长度) -> NCLOB"));
        oracleRules.add(new TypeMappingRule("CHARACTER\\((\\d+)\\)", "NCHAR($1)", 44, false, "CHARACTER -> NCHAR (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "NCHAR($1)", 45, false, "CHAR -> NCHAR (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("TEXT", "NCLOB", 46, false, "TEXT -> NCLOB (Unicode 兼容)"));

        // ==================== 日期时间类型 ====================
        oracleRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        oracleRules.add(new TypeMappingRule("TIME WITHOUT TIME ZONE", "VARCHAR2(20)", 51, true, "TIME WITHOUT TIME ZONE -> VARCHAR2(20)"));
        oracleRules.add(new TypeMappingRule("TIME WITH TIME ZONE", "VARCHAR2(20)", 52, true, "TIME WITH TIME ZONE -> VARCHAR2(20)"));
        oracleRules.add(new TypeMappingRule("TIME", "VARCHAR2(20)", 53, true, "TIME -> VARCHAR2(20) (Oracle无TIME类型)"));
        oracleRules.add(new TypeMappingRule("TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP", 54, false, "TIMESTAMP WITHOUT TIME ZONE -> TIMESTAMP"));
        oracleRules.add(new TypeMappingRule("TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE", 55, false, "TIMESTAMPTZ 兼容"));
        oracleRules.add(new TypeMappingRule("TIMESTAMP", "TIMESTAMP", 56, false, "TIMESTAMP 兼容"));

        // ==================== 二进制类型 ====================
        oracleRules.add(new TypeMappingRule("BYTEA", "BLOB", 60, false, "BYTEA -> BLOB"));

        // ==================== 其他类型 ====================
        oracleRules.add(new TypeMappingRule("BOOLEAN", "NUMBER(1)", 70, false, "BOOLEAN -> NUMBER(1)"));
        oracleRules.add(new TypeMappingRule("UUID", "VARCHAR2(36)", 71, false, "UUID -> VARCHAR2(36)"));
        oracleRules.add(new TypeMappingRule("JSON", "CLOB", 72, false, "JSON -> CLOB"));
        oracleRules.add(new TypeMappingRule("JSONB", "CLOB", 73, false, "JSONB -> CLOB"));

        // ==================== 通配符兜底规则 ====================
        oracleRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        oracleRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("ORACLE", oracleRules);
        log.info("Initialized {} PostgreSQL->Oracle type mapping rules", oracleRules.size());
    }
}
