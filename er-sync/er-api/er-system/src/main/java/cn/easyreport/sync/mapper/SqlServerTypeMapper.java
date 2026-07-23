package cn.easyreport.sync.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * SQL Server 类型映射器
 *
 * 负责将 SQL Server 数据类型映射到其他数据库系统
 */
public class SqlServerTypeMapper implements DatabaseTypeMapper {

    private static final Logger log = LoggerFactory.getLogger(SqlServerTypeMapper.class);

    private static final String SOURCE_DB_TYPE = "SQLSERVER";

    private final Map<String, List<TypeMappingRule>> mappingRules = new HashMap<>();

    public SqlServerTypeMapper() {
        initializeMySqlMappings();
        initializeTiDbMappings();
        initializeStarRocksMappings();
        initializeSqlServerMappings();
        initializePostgreSqlMappings();
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
                log.debug("Mapped SQL Server type '{}' to {} type '{}' using rule: {}",
                        sourceType, targetDbType, result, rule.getSourcePattern());
                return result;
            }
        }

        log.debug("No matching rule for SQL Server type '{}' to {}, keeping original", sourceType, targetDbType);
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

    private void initializeSqlServerMappings() {
        List<TypeMappingRule> sqlserverRules = new ArrayList<>();
        sqlserverRules.add(new TypeMappingRule(".*", "$0", 999, false, "SQL Server同源映射"));
        mappingRules.put("SQLSERVER", sqlserverRules);
        log.info("Initialized {} SQL Server->SQL Server type mapping rules", sqlserverRules.size());
    }

    private void initializeMySqlMappings() {
        List<TypeMappingRule> mysqlRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        mysqlRules.add(new TypeMappingRule("TINYINT", "TINYINT", 10, false, "TINYINT 兼容"));
        mysqlRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 11, false, "SMALLINT 兼容"));
        mysqlRules.add(new TypeMappingRule("INT", "INT", 12, false, "INT 兼容"));
        mysqlRules.add(new TypeMappingRule("BIGINT", "BIGINT", 13, false, "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        mysqlRules.add(new TypeMappingRule("REAL", "FLOAT", 20, false, "REAL -> FLOAT"));
        mysqlRules.add(new TypeMappingRule("FLOAT", "DOUBLE", 21, false, "FLOAT -> DOUBLE"));

        // ==================== 精确数值类型 ====================
        mysqlRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false, "DECIMAL 兼容"));
        mysqlRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 31, false, "NUMERIC -> DECIMAL"));

        // ==================== 字符串类型 ====================
        mysqlRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false, "CHAR 兼容"));
        mysqlRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 41, false, "VARCHAR 兼容"));
        mysqlRules.add(new TypeMappingRule("VARCHAR\\(MAX\\)", "TEXT", 42, false, "VARCHAR(MAX) -> TEXT"));
        mysqlRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "CHAR($1)", 43, false, "NCHAR -> CHAR"));
        mysqlRules.add(new TypeMappingRule("NVARCHAR\\((\\d+)\\)", "VARCHAR($1)", 44, false, "NVARCHAR -> VARCHAR"));
        mysqlRules.add(new TypeMappingRule("NVARCHAR\\(MAX\\)", "TEXT", 45, false, "NVARCHAR(MAX) -> TEXT"));
        mysqlRules.add(new TypeMappingRule("TEXT", "TEXT", 46, false, "TEXT 兼容"));
        mysqlRules.add(new TypeMappingRule("NTEXT", "TEXT", 47, false, "NTEXT -> TEXT"));

        // ==================== 日期时间类型 ====================
        mysqlRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        mysqlRules.add(new TypeMappingRule("TIME", "TIME", 51, false, "TIME 兼容"));
        mysqlRules.add(new TypeMappingRule("DATETIME", "DATETIME", 52, false, "DATETIME 兼容"));
        mysqlRules.add(new TypeMappingRule("DATETIME2", "DATETIME", 53, false, "DATETIME2 -> DATETIME"));
        mysqlRules.add(new TypeMappingRule("SMALLDATETIME", "DATETIME", 54, false, "SMALLDATETIME -> DATETIME"));
        mysqlRules.add(new TypeMappingRule("DATETIMEOFFSET", "DATETIME", 55, true, "DATETIMEOFFSET -> DATETIME (丢失时区)"));

        // ==================== 二进制类型 ====================
        mysqlRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "BINARY($1)", 60, false, "BINARY 兼容"));
        mysqlRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "VARBINARY($1)", 61, false, "VARBINARY 兼容"));
        mysqlRules.add(new TypeMappingRule("VARBINARY\\(MAX\\)", "LONGBLOB", 62, false, "VARBINARY(MAX) -> LONGBLOB"));
        mysqlRules.add(new TypeMappingRule("IMAGE", "LONGBLOB", 63, false, "IMAGE -> LONGBLOB"));

        // ==================== 其他类型 ====================
        mysqlRules.add(new TypeMappingRule("BIT", "TINYINT(1)", 70, false, "BIT -> TINYINT(1)"));
        mysqlRules.add(new TypeMappingRule("UNIQUEIDENTIFIER", "CHAR(36)", 71, false, "UNIQUEIDENTIFIER -> CHAR(36)"));

        // ==================== 通配符兜底规则 ====================
        mysqlRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        mysqlRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("MYSQL", mysqlRules);
        log.info("Initialized {} SQL Server->MySQL type mapping rules", mysqlRules.size());
    }

    private void initializeTiDbMappings() {
        List<TypeMappingRule> tidbRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        tidbRules.add(new TypeMappingRule("TINYINT", "TINYINT", 10, false, "TINYINT 兼容"));
        tidbRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 11, false, "SMALLINT 兼容"));
        tidbRules.add(new TypeMappingRule("INT", "INT", 12, false, "INT 兼容"));
        tidbRules.add(new TypeMappingRule("BIGINT", "BIGINT", 13, false, "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        tidbRules.add(new TypeMappingRule("REAL", "FLOAT", 20, false, "REAL -> FLOAT"));
        tidbRules.add(new TypeMappingRule("FLOAT", "DOUBLE", 21, false, "FLOAT -> DOUBLE"));

        // ==================== 精确数值类型 ====================
        tidbRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false, "DECIMAL 兼容"));
        tidbRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 31, false, "NUMERIC -> DECIMAL"));

        // ==================== 字符串类型 ====================
        tidbRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false, "CHAR 兼容"));
        tidbRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 41, false, "VARCHAR 兼容"));
        tidbRules.add(new TypeMappingRule("VARCHAR\\(MAX\\)", "TEXT", 42, false, "VARCHAR(MAX) -> TEXT"));
        tidbRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "CHAR($1)", 43, false, "NCHAR -> CHAR"));
        tidbRules.add(new TypeMappingRule("NVARCHAR\\((\\d+)\\)", "VARCHAR($1)", 44, false, "NVARCHAR -> VARCHAR"));
        tidbRules.add(new TypeMappingRule("NVARCHAR\\(MAX\\)", "TEXT", 45, false, "NVARCHAR(MAX) -> TEXT"));
        tidbRules.add(new TypeMappingRule("TEXT", "TEXT", 46, false, "TEXT 兼容"));
        tidbRules.add(new TypeMappingRule("NTEXT", "TEXT", 47, false, "NTEXT -> TEXT"));

        // ==================== 日期时间类型 ====================
        tidbRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        tidbRules.add(new TypeMappingRule("TIME", "TIME", 51, false, "TIME 兼容"));
        tidbRules.add(new TypeMappingRule("DATETIME", "DATETIME", 52, false, "DATETIME 兼容"));
        tidbRules.add(new TypeMappingRule("DATETIME2", "DATETIME", 53, false, "DATETIME2 -> DATETIME"));
        tidbRules.add(new TypeMappingRule("SMALLDATETIME", "DATETIME", 54, false, "SMALLDATETIME -> DATETIME"));
        tidbRules.add(new TypeMappingRule("DATETIMEOFFSET", "DATETIME", 55, true, "DATETIMEOFFSET -> DATETIME (丢失时区)"));

        // ==================== 二进制类型 ====================
        tidbRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "BINARY($1)", 60, false, "BINARY 兼容"));
        tidbRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "VARBINARY($1)", 61, false, "VARBINARY 兼容"));
        tidbRules.add(new TypeMappingRule("VARBINARY\\(MAX\\)", "LONGBLOB", 62, false, "VARBINARY(MAX) -> LONGBLOB"));
        tidbRules.add(new TypeMappingRule("IMAGE", "LONGBLOB", 63, false, "IMAGE -> LONGBLOB"));

        // ==================== 其他类型 ====================
        tidbRules.add(new TypeMappingRule("BIT", "TINYINT(1)", 70, false, "BIT -> TINYINT(1)"));
        tidbRules.add(new TypeMappingRule("UNIQUEIDENTIFIER", "CHAR(36)", 71, false, "UNIQUEIDENTIFIER -> CHAR(36)"));

        // ==================== 通配符兜底规则 ====================
        tidbRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        tidbRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("TIDB", tidbRules);
        log.info("Initialized {} SQL Server->TiDB type mapping rules", tidbRules.size());
    }

    private void initializeStarRocksMappings() {
        List<TypeMappingRule> starrocksRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        starrocksRules.add(new TypeMappingRule("TINYINT", "TINYINT", 10, false, "TINYINT 兼容"));
        starrocksRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 11, false, "SMALLINT 兼容"));
        starrocksRules.add(new TypeMappingRule("INT", "INT", 12, false, "INT 兼容"));
        starrocksRules.add(new TypeMappingRule("BIGINT", "BIGINT", 13, false, "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        starrocksRules.add(new TypeMappingRule("REAL", "FLOAT", 20, false, "REAL -> FLOAT"));
        starrocksRules.add(new TypeMappingRule("FLOAT", "DOUBLE", 21, false, "FLOAT -> DOUBLE"));

        // ==================== 精确数值类型 ====================
        starrocksRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false, "DECIMAL 兼容"));
        starrocksRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 31, false, "NUMERIC -> DECIMAL"));

        // ==================== 字符串类型 ====================
        starrocksRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "STRING", 40, false, "CHAR -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "STRING", 41, false, "VARCHAR -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("VARCHAR\\(MAX\\)", "STRING", 42, false, "VARCHAR(MAX) -> STRING"));
        starrocksRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "STRING", 43, false, "NCHAR -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("NVARCHAR\\((\\d+)\\)", "STRING", 44, false, "NVARCHAR -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("NVARCHAR\\(MAX\\)", "STRING", 45, false, "NVARCHAR(MAX) -> STRING"));
        starrocksRules.add(new TypeMappingRule("TEXT", "STRING", 46, false, "TEXT -> STRING"));
        starrocksRules.add(new TypeMappingRule("NTEXT", "STRING", 47, false, "NTEXT -> STRING"));

        // ==================== 日期时间类型 ====================
        starrocksRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        starrocksRules.add(new TypeMappingRule("TIME", "VARCHAR(20)", 51, true, "TIME -> VARCHAR(20) (无原生TIME)"));
        starrocksRules.add(new TypeMappingRule("DATETIME", "DATETIME", 52, false, "DATETIME 兼容"));
        starrocksRules.add(new TypeMappingRule("DATETIME2", "DATETIME", 53, false, "DATETIME2 -> DATETIME"));
        starrocksRules.add(new TypeMappingRule("SMALLDATETIME", "DATETIME", 54, false, "SMALLDATETIME -> DATETIME"));
        starrocksRules.add(new TypeMappingRule("DATETIMEOFFSET", "DATETIME", 55, true, "DATETIMEOFFSET -> DATETIME (丢失时区)"));

        // ==================== 二进制类型 ====================
        // StarRocks 支持 VARBINARY(n)，最大约 1MB；不支持 MAX 关键字
        starrocksRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "VARBINARY($1)", 60, false, "BINARY -> VARBINARY"));
        starrocksRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "VARBINARY($1)", 61, false, "VARBINARY 兼容"));
        starrocksRules.add(new TypeMappingRule("VARBINARY\\(MAX\\)", "VARBINARY(1048576)", 62, true, "VARBINARY(MAX) -> VARBINARY(1048576) (有损，最大1MB)"));
        starrocksRules.add(new TypeMappingRule("IMAGE", "VARBINARY(1048576)", 63, true, "IMAGE -> VARBINARY(1048576) (有损)"));

        // ==================== 其他类型 ====================
        starrocksRules.add(new TypeMappingRule("BIT", "BOOLEAN", 70, false, "BIT -> BOOLEAN"));
        starrocksRules.add(new TypeMappingRule("UNIQUEIDENTIFIER", "VARCHAR(36)", 71, false, "UNIQUEIDENTIFIER -> VARCHAR(36)"));

        // ==================== 通配符兜底规则 ====================
        starrocksRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        starrocksRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("STARROCKS", starrocksRules);
        log.info("Initialized {} SQL Server->StarRocks type mapping rules", starrocksRules.size());
    }

    private void initializePostgreSqlMappings() {
        List<TypeMappingRule> pgRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        pgRules.add(new TypeMappingRule("TINYINT", "SMALLINT", 10, false, "TINYINT -> SMALLINT (PG无TINYINT)"));
        pgRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 11, false, "SMALLINT 兼容"));
        pgRules.add(new TypeMappingRule("INT", "INTEGER", 12, false, "INT -> INTEGER"));
        pgRules.add(new TypeMappingRule("BIGINT", "BIGINT", 13, false, "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        pgRules.add(new TypeMappingRule("REAL", "REAL", 20, false, "REAL 兼容"));
        pgRules.add(new TypeMappingRule("FLOAT", "DOUBLE PRECISION", 21, false, "FLOAT -> DOUBLE PRECISION"));

        // ==================== 精确数值类型 ====================
        pgRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "NUMERIC($1,$2)", 30, false, "DECIMAL -> NUMERIC"));
        pgRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "NUMERIC($1,$2)", 31, false, "NUMERIC 兼容"));
        pgRules.add(new TypeMappingRule("MONEY", "NUMERIC(19,4)", 32, false, "MONEY -> NUMERIC(19,4)"));
        pgRules.add(new TypeMappingRule("SMALLMONEY", "NUMERIC(10,4)", 33, false, "SMALLMONEY -> NUMERIC(10,4)"));

        // ==================== 字符串类型 ====================
        pgRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false, "CHAR 兼容"));
        pgRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 41, false, "VARCHAR 兼容"));
        pgRules.add(new TypeMappingRule("VARCHAR\\(MAX\\)", "TEXT", 42, false, "VARCHAR(MAX) -> TEXT"));
        pgRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "CHAR($1)", 43, false, "NCHAR -> CHAR"));
        pgRules.add(new TypeMappingRule("NVARCHAR\\((\\d+)\\)", "VARCHAR($1)", 44, false, "NVARCHAR -> VARCHAR"));
        pgRules.add(new TypeMappingRule("NVARCHAR\\(MAX\\)", "TEXT", 45, false, "NVARCHAR(MAX) -> TEXT"));
        pgRules.add(new TypeMappingRule("TEXT", "TEXT", 46, false, "TEXT 兼容"));
        pgRules.add(new TypeMappingRule("NTEXT", "TEXT", 47, false, "NTEXT -> TEXT"));

        // ==================== 日期时间类型 ====================
        pgRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        pgRules.add(new TypeMappingRule("TIME", "TIME", 51, false, "TIME 兼容"));
        pgRules.add(new TypeMappingRule("DATETIME", "TIMESTAMP", 52, false, "DATETIME -> TIMESTAMP"));
        pgRules.add(new TypeMappingRule("DATETIME2", "TIMESTAMP", 53, false, "DATETIME2 -> TIMESTAMP"));
        pgRules.add(new TypeMappingRule("SMALLDATETIME", "TIMESTAMP", 54, false, "SMALLDATETIME -> TIMESTAMP"));
        pgRules.add(new TypeMappingRule("DATETIMEOFFSET", "TIMESTAMPTZ", 55, false, "DATETIMEOFFSET -> TIMESTAMPTZ"));

        // ==================== 二进制类型 ====================
        pgRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "BYTEA", 60, false, "BINARY -> BYTEA"));
        pgRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "BYTEA", 61, false, "VARBINARY -> BYTEA"));
        pgRules.add(new TypeMappingRule("VARBINARY\\(MAX\\)", "BYTEA", 62, false, "VARBINARY(MAX) -> BYTEA"));
        pgRules.add(new TypeMappingRule("IMAGE", "BYTEA", 63, false, "IMAGE -> BYTEA"));

        // ==================== 其他类型 ====================
        pgRules.add(new TypeMappingRule("BIT", "BOOLEAN", 70, false, "BIT -> BOOLEAN"));
        pgRules.add(new TypeMappingRule("UNIQUEIDENTIFIER", "UUID", 71, false, "UNIQUEIDENTIFIER -> UUID"));
        pgRules.add(new TypeMappingRule("XML", "XML", 72, false, "XML 兼容"));

        // ==================== 通配符兜底规则 ====================
        pgRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        pgRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("POSTGRESQL", pgRules);
        log.info("Initialized {} SQL Server->PostgreSQL type mapping rules", pgRules.size());
    }

    private void initializeOracleMappings() {
        List<TypeMappingRule> oracleRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        oracleRules.add(new TypeMappingRule("TINYINT", "NUMBER(3)", 10, false, "TINYINT -> NUMBER(3)"));
        oracleRules.add(new TypeMappingRule("SMALLINT", "NUMBER(5)", 11, false, "SMALLINT -> NUMBER(5)"));
        oracleRules.add(new TypeMappingRule("INT", "NUMBER(10)", 12, false, "INT -> NUMBER(10)"));
        oracleRules.add(new TypeMappingRule("BIGINT", "NUMBER(19)", 13, false, "BIGINT -> NUMBER(19)"));

        // ==================== 浮点类型 ====================
        oracleRules.add(new TypeMappingRule("REAL", "BINARY_FLOAT", 20, false, "REAL -> BINARY_FLOAT"));
        oracleRules.add(new TypeMappingRule("FLOAT", "BINARY_DOUBLE", 21, false, "FLOAT -> BINARY_DOUBLE"));

        // ==================== 精确数值类型 ====================
        oracleRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "NUMBER($1,$2)", 30, false, "DECIMAL -> NUMBER"));
        oracleRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)", "NUMBER($1,$2)", 31, false, "NUMERIC -> NUMBER"));
        oracleRules.add(new TypeMappingRule("MONEY", "NUMBER(19,4)", 32, false, "MONEY -> NUMBER(19,4)"));
        oracleRules.add(new TypeMappingRule("SMALLMONEY", "NUMBER(10,4)", 33, false, "SMALLMONEY -> NUMBER(10,4)"));

        // ==================== 字符串类型 ====================
        oracleRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false, "CHAR 兼容"));
        oracleRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR2($1)", 41, false, "VARCHAR -> VARCHAR2"));
        oracleRules.add(new TypeMappingRule("VARCHAR\\(MAX\\)", "CLOB", 42, false, "VARCHAR(MAX) -> CLOB"));
        oracleRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "NCHAR($1)", 43, false, "NCHAR 兼容"));
        oracleRules.add(new TypeMappingRule("NVARCHAR\\((\\d+)\\)", "NVARCHAR2($1)", 44, false, "NVARCHAR -> NVARCHAR2"));
        oracleRules.add(new TypeMappingRule("NVARCHAR\\(MAX\\)", "NCLOB", 45, false, "NVARCHAR(MAX) -> NCLOB"));
        oracleRules.add(new TypeMappingRule("TEXT", "CLOB", 46, false, "TEXT -> CLOB"));
        oracleRules.add(new TypeMappingRule("NTEXT", "NCLOB", 47, false, "NTEXT -> NCLOB"));

        // ==================== 日期时间类型 ====================
        oracleRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        oracleRules.add(new TypeMappingRule("TIME", "VARCHAR2(20)", 51, true, "TIME -> VARCHAR2(20) (Oracle无TIME)"));
        oracleRules.add(new TypeMappingRule("DATETIME", "TIMESTAMP", 52, false, "DATETIME -> TIMESTAMP"));
        oracleRules.add(new TypeMappingRule("DATETIME2", "TIMESTAMP", 53, false, "DATETIME2 -> TIMESTAMP"));
        oracleRules.add(new TypeMappingRule("SMALLDATETIME", "TIMESTAMP", 54, false, "SMALLDATETIME -> TIMESTAMP"));
        oracleRules.add(new TypeMappingRule("DATETIMEOFFSET", "TIMESTAMP WITH TIME ZONE", 55, false, "DATETIMEOFFSET -> TIMESTAMP WITH TIME ZONE"));

        // ==================== 二进制类型 ====================
        oracleRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "RAW($1)", 60, false, "BINARY -> RAW"));
        oracleRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "RAW($1)", 61, false, "VARBINARY -> RAW"));
        oracleRules.add(new TypeMappingRule("VARBINARY\\(MAX\\)", "BLOB", 62, false, "VARBINARY(MAX) -> BLOB"));
        oracleRules.add(new TypeMappingRule("IMAGE", "BLOB", 63, false, "IMAGE -> BLOB"));

        // ==================== 其他类型 ====================
        oracleRules.add(new TypeMappingRule("BIT", "NUMBER(1)", 70, false, "BIT -> NUMBER(1)"));
        oracleRules.add(new TypeMappingRule("UNIQUEIDENTIFIER", "VARCHAR2(36)", 71, false, "UNIQUEIDENTIFIER -> VARCHAR2(36)"));
        oracleRules.add(new TypeMappingRule("XML", "CLOB", 72, true, "XML -> CLOB (lossy)"));

        // ==================== 通配符兜底规则 ====================
        oracleRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        oracleRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("ORACLE", oracleRules);
        log.info("Initialized {} SQL Server->Oracle type mapping rules", oracleRules.size());
    }
}
