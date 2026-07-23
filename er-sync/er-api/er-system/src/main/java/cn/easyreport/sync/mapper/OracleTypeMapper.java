package cn.easyreport.sync.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Oracle 类型映射器
 *
 * 负责将 Oracle 数据类型映射到其他数据库系统
 */
public class OracleTypeMapper implements DatabaseTypeMapper {

    private static final Logger log = LoggerFactory.getLogger(OracleTypeMapper.class);

    private static final String SOURCE_DB_TYPE = "ORACLE";

    private final Map<String, List<TypeMappingRule>> mappingRules = new HashMap<>();

    public OracleTypeMapper() {
        initializeOracleMappings();
        initializeMySqlMappings();
        initializeTiDbMappings();
        initializeStarRocksMappings();
        initializeSqlServerMappings();
        initializePostgreSqlMappings();
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
                log.debug("Mapped Oracle type '{}' to {} type '{}' using rule: {}",
                        sourceType, targetDbType, result, rule.getSourcePattern());
                return result;
            }
        }

        log.debug("No matching rule for Oracle type '{}' to {}, keeping original", sourceType, targetDbType);
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

    // ==================== Oracle -> Oracle 同源映射 ====================

    private void initializeOracleMappings() {
        List<TypeMappingRule> oracleRules = new ArrayList<>();
        oracleRules.add(new TypeMappingRule(".*", "$0", 999, false, "Oracle同源映射"));
        mappingRules.put("ORACLE", oracleRules);
        log.info("Initialized {} Oracle->Oracle type mapping rules", oracleRules.size());
    }

    // ==================== Oracle -> MySQL ====================

    private void initializeMySqlMappings() {
        List<TypeMappingRule> mysqlRules = new ArrayList<>();

        // ==================== 数值类型 ====================
        // NUMBER(p,s) 有小数位 -> DECIMAL(p,s)
        mysqlRules.add(new TypeMappingRule("NUMBER\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 10, false, "NUMBER(p,s) -> DECIMAL(p,s)"));

        // NUMBER(1-2) -> TINYINT
        mysqlRules.add(new TypeMappingRule("NUMBER\\([12]\\)", "TINYINT", 20, false, "NUMBER(1-2) -> TINYINT"));
        // NUMBER(3-4) -> SMALLINT
        mysqlRules.add(new TypeMappingRule("NUMBER\\([34]\\)", "SMALLINT", 21, false, "NUMBER(3-4) -> SMALLINT"));
        // NUMBER(5-9) -> INT
        mysqlRules.add(new TypeMappingRule("NUMBER\\([5-9]\\)", "INT", 22, false, "NUMBER(5-9) -> INT"));
        // NUMBER(10-18) -> BIGINT
        mysqlRules.add(new TypeMappingRule("NUMBER\\(1[0-8]\\)", "BIGINT", 23, false, "NUMBER(10-18) -> BIGINT"));
        // NUMBER(19+) -> DECIMAL
        mysqlRules.add(new TypeMappingRule("NUMBER\\((\\d{2,})\\)", "DECIMAL($1,0)", 24, false, "NUMBER(19+) -> DECIMAL(p,0)"));

        // NUMBER 无精度 -> DECIMAL(38,10)
        mysqlRules.add(new TypeMappingRule("NUMBER", "DECIMAL(38,10)", 25, false, "NUMBER -> DECIMAL(38,10)"));

        // ==================== 浮点类型 ====================
        mysqlRules.add(new TypeMappingRule("BINARY_FLOAT", "FLOAT", 30, false, "BINARY_FLOAT -> FLOAT"));
        mysqlRules.add(new TypeMappingRule("BINARY_DOUBLE", "DOUBLE", 31, false, "BINARY_DOUBLE -> DOUBLE"));
        mysqlRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+\\))?", "DOUBLE", 32, false, "FLOAT -> DOUBLE"));

        // ==================== 字符串类型 ====================
        mysqlRules.add(new TypeMappingRule("VARCHAR2\\((\\d+)\\)", "VARCHAR($1)", 40, false, "VARCHAR2 -> VARCHAR"));
        mysqlRules.add(new TypeMappingRule("NVARCHAR2\\((\\d+)\\)", "VARCHAR($1)", 41, false, "NVARCHAR2 -> VARCHAR"));
        mysqlRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 42, false, "CHAR 兼容"));
        mysqlRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "CHAR($1)", 43, false, "NCHAR -> CHAR"));
        mysqlRules.add(new TypeMappingRule("CLOB", "LONGTEXT", 44, false, "CLOB -> LONGTEXT"));
        mysqlRules.add(new TypeMappingRule("NCLOB", "LONGTEXT", 45, false, "NCLOB -> LONGTEXT"));
        mysqlRules.add(new TypeMappingRule("LONG", "LONGTEXT", 46, false, "LONG -> LONGTEXT"));

        // ==================== 二进制类型 ====================
        mysqlRules.add(new TypeMappingRule("BLOB", "LONGBLOB", 50, false, "BLOB -> LONGBLOB"));
        mysqlRules.add(new TypeMappingRule("RAW\\((\\d+)\\)", "VARBINARY($1)", 51, false, "RAW -> VARBINARY"));
        mysqlRules.add(new TypeMappingRule("LONG RAW", "LONGBLOB", 52, false, "LONG RAW -> LONGBLOB"));

        // ==================== 日期时间类型 ====================
        mysqlRules.add(new TypeMappingRule("DATE", "DATETIME", 60, false, "DATE -> DATETIME"));
        mysqlRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH TIME ZONE", "DATETIME", 61, true, "TIMESTAMP WITH TZ -> DATETIME (丢失时区)"));
        mysqlRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH LOCAL TIME ZONE", "DATETIME", 62, true, "TIMESTAMP WITH LOCAL TZ -> DATETIME (丢失时区)"));
        mysqlRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "TIMESTAMP", 63, false, "TIMESTAMP -> TIMESTAMP"));

        // ==================== 其他类型 ====================
        mysqlRules.add(new TypeMappingRule("XMLTYPE", "LONGTEXT", 70, true, "XMLTYPE -> LONGTEXT (有损)"));
        mysqlRules.add(new TypeMappingRule("ROWID", "VARCHAR(18)", 71, false, "ROWID -> VARCHAR(18)"));

        // ==================== 通配符兜底规则 ====================
        mysqlRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        mysqlRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("MYSQL", mysqlRules);
        log.info("Initialized {} Oracle->MySQL type mapping rules", mysqlRules.size());
    }

    // ==================== Oracle -> TiDB ====================

    private void initializeTiDbMappings() {
        List<TypeMappingRule> tidbRules = new ArrayList<>();

        // ==================== 数值类型 ====================
        tidbRules.add(new TypeMappingRule("NUMBER\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 10, false, "NUMBER(p,s) -> DECIMAL(p,s)"));
        tidbRules.add(new TypeMappingRule("NUMBER\\([12]\\)", "TINYINT", 20, false, "NUMBER(1-2) -> TINYINT"));
        tidbRules.add(new TypeMappingRule("NUMBER\\([34]\\)", "SMALLINT", 21, false, "NUMBER(3-4) -> SMALLINT"));
        tidbRules.add(new TypeMappingRule("NUMBER\\([5-9]\\)", "INT", 22, false, "NUMBER(5-9) -> INT"));
        tidbRules.add(new TypeMappingRule("NUMBER\\(1[0-8]\\)", "BIGINT", 23, false, "NUMBER(10-18) -> BIGINT"));
        tidbRules.add(new TypeMappingRule("NUMBER\\((\\d{2,})\\)", "DECIMAL($1,0)", 24, false, "NUMBER(19+) -> DECIMAL(p,0)"));
        tidbRules.add(new TypeMappingRule("NUMBER", "DECIMAL(38,10)", 25, false, "NUMBER -> DECIMAL(38,10)"));

        // ==================== 浮点类型 ====================
        tidbRules.add(new TypeMappingRule("BINARY_FLOAT", "FLOAT", 30, false, "BINARY_FLOAT -> FLOAT"));
        tidbRules.add(new TypeMappingRule("BINARY_DOUBLE", "DOUBLE", 31, false, "BINARY_DOUBLE -> DOUBLE"));
        tidbRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+\\))?", "DOUBLE", 32, false, "FLOAT -> DOUBLE"));

        // ==================== 字符串类型 ====================
        tidbRules.add(new TypeMappingRule("VARCHAR2\\((\\d+)\\)", "VARCHAR($1)", 40, false, "VARCHAR2 -> VARCHAR"));
        tidbRules.add(new TypeMappingRule("NVARCHAR2\\((\\d+)\\)", "VARCHAR($1)", 41, false, "NVARCHAR2 -> VARCHAR"));
        tidbRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 42, false, "CHAR 兼容"));
        tidbRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "CHAR($1)", 43, false, "NCHAR -> CHAR"));
        tidbRules.add(new TypeMappingRule("CLOB", "LONGTEXT", 44, false, "CLOB -> LONGTEXT"));
        tidbRules.add(new TypeMappingRule("NCLOB", "LONGTEXT", 45, false, "NCLOB -> LONGTEXT"));
        tidbRules.add(new TypeMappingRule("LONG", "LONGTEXT", 46, false, "LONG -> LONGTEXT"));

        // ==================== 二进制类型 ====================
        tidbRules.add(new TypeMappingRule("BLOB", "LONGBLOB", 50, false, "BLOB -> LONGBLOB"));
        tidbRules.add(new TypeMappingRule("RAW\\((\\d+)\\)", "VARBINARY($1)", 51, false, "RAW -> VARBINARY"));
        tidbRules.add(new TypeMappingRule("LONG RAW", "LONGBLOB", 52, false, "LONG RAW -> LONGBLOB"));

        // ==================== 日期时间类型 ====================
        tidbRules.add(new TypeMappingRule("DATE", "DATETIME", 60, false, "DATE -> DATETIME"));
        tidbRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH TIME ZONE", "DATETIME", 61, true, "TIMESTAMP WITH TZ -> DATETIME (丢失时区)"));
        tidbRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH LOCAL TIME ZONE", "DATETIME", 62, true, "TIMESTAMP WITH LOCAL TZ -> DATETIME (丢失时区)"));
        tidbRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "TIMESTAMP", 63, false, "TIMESTAMP -> TIMESTAMP"));

        // ==================== 其他类型 ====================
        tidbRules.add(new TypeMappingRule("XMLTYPE", "LONGTEXT", 70, true, "XMLTYPE -> LONGTEXT (有损)"));
        tidbRules.add(new TypeMappingRule("ROWID", "VARCHAR(18)", 71, false, "ROWID -> VARCHAR(18)"));

        // ==================== 通配符兜底规则 ====================
        tidbRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        tidbRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("TIDB", tidbRules);
        log.info("Initialized {} Oracle->TiDB type mapping rules", tidbRules.size());
    }

    // ==================== Oracle -> PostgreSQL ====================

    private void initializePostgreSqlMappings() {
        List<TypeMappingRule> pgRules = new ArrayList<>();

        // ==================== 数值类型 ====================
        pgRules.add(new TypeMappingRule("NUMBER\\((\\d+),(\\d+)\\)", "NUMERIC($1,$2)", 10, false, "NUMBER(p,s) -> NUMERIC(p,s)"));
        pgRules.add(new TypeMappingRule("NUMBER\\([12]\\)", "SMALLINT", 20, false, "NUMBER(1-2) -> SMALLINT"));
        pgRules.add(new TypeMappingRule("NUMBER\\([3-9]\\)", "INTEGER", 21, false, "NUMBER(3-9) -> INTEGER"));
        pgRules.add(new TypeMappingRule("NUMBER\\(1[0-8]\\)", "BIGINT", 22, false, "NUMBER(10-18) -> BIGINT"));
        pgRules.add(new TypeMappingRule("NUMBER\\((\\d{2,})\\)", "NUMERIC($1,0)", 23, false, "NUMBER(19+) -> NUMERIC(p,0)"));
        pgRules.add(new TypeMappingRule("NUMBER", "NUMERIC", 24, false, "NUMBER -> NUMERIC"));

        // ==================== 浮点类型 ====================
        pgRules.add(new TypeMappingRule("BINARY_FLOAT", "REAL", 30, false, "BINARY_FLOAT -> REAL"));
        pgRules.add(new TypeMappingRule("BINARY_DOUBLE", "DOUBLE PRECISION", 31, false, "BINARY_DOUBLE -> DOUBLE PRECISION"));
        pgRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+\\))?", "DOUBLE PRECISION", 32, false, "FLOAT -> DOUBLE PRECISION"));

        // ==================== 字符串类型 ====================
        pgRules.add(new TypeMappingRule("VARCHAR2\\((\\d+)\\)", "VARCHAR($1)", 40, false, "VARCHAR2 -> VARCHAR"));
        pgRules.add(new TypeMappingRule("NVARCHAR2\\((\\d+)\\)", "VARCHAR($1)", 41, false, "NVARCHAR2 -> VARCHAR"));
        pgRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 42, false, "CHAR 兼容"));
        pgRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "CHAR($1)", 43, false, "NCHAR -> CHAR"));
        pgRules.add(new TypeMappingRule("CLOB", "TEXT", 44, false, "CLOB -> TEXT"));
        pgRules.add(new TypeMappingRule("NCLOB", "TEXT", 45, false, "NCLOB -> TEXT"));
        pgRules.add(new TypeMappingRule("LONG", "TEXT", 46, false, "LONG -> TEXT"));

        // ==================== 二进制类型 ====================
        pgRules.add(new TypeMappingRule("BLOB", "BYTEA", 50, false, "BLOB -> BYTEA"));
        pgRules.add(new TypeMappingRule("RAW\\((\\d+)\\)", "BYTEA", 51, false, "RAW -> BYTEA"));
        pgRules.add(new TypeMappingRule("LONG RAW", "BYTEA", 52, false, "LONG RAW -> BYTEA"));

        // ==================== 日期时间类型 ====================
        pgRules.add(new TypeMappingRule("DATE", "TIMESTAMP", 60, false, "DATE -> TIMESTAMP"));
        pgRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE", 61, false, "TIMESTAMP WITH TZ 兼容"));
        pgRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH LOCAL TIME ZONE", "TIMESTAMP WITH TIME ZONE", 62, false, "TIMESTAMP WITH LOCAL TZ -> TIMESTAMPTZ"));
        pgRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "TIMESTAMP", 63, false, "TIMESTAMP 兼容"));

        // ==================== 其他类型 ====================
        pgRules.add(new TypeMappingRule("XMLTYPE", "XML", 70, false, "XMLTYPE -> XML"));

        // ==================== 通配符兜底规则 ====================
        pgRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        pgRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("POSTGRESQL", pgRules);
        log.info("Initialized {} Oracle->PostgreSQL type mapping rules", pgRules.size());
    }

    // ==================== Oracle -> SQL Server ====================

    private void initializeSqlServerMappings() {
        List<TypeMappingRule> sqlserverRules = new ArrayList<>();

        // ==================== 数值类型 ====================
        sqlserverRules.add(new TypeMappingRule("NUMBER\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 10, false, "NUMBER(p,s) -> DECIMAL(p,s)"));
        sqlserverRules.add(new TypeMappingRule("NUMBER\\([1-9]\\)", "INT", 20, false, "NUMBER(1-9) -> INT"));
        sqlserverRules.add(new TypeMappingRule("NUMBER\\(1[0-8]\\)", "BIGINT", 21, false, "NUMBER(10-18) -> BIGINT"));
        sqlserverRules.add(new TypeMappingRule("NUMBER\\((\\d{2,})\\)", "DECIMAL($1,0)", 22, false, "NUMBER(19+) -> DECIMAL(p,0)"));
        sqlserverRules.add(new TypeMappingRule("NUMBER", "DECIMAL(38,10)", 23, false, "NUMBER -> DECIMAL(38,10)"));

        // ==================== 浮点类型 ====================
        sqlserverRules.add(new TypeMappingRule("BINARY_FLOAT", "REAL", 30, false, "BINARY_FLOAT -> REAL"));
        sqlserverRules.add(new TypeMappingRule("BINARY_DOUBLE", "FLOAT", 31, false, "BINARY_DOUBLE -> FLOAT"));
        sqlserverRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+\\))?", "FLOAT", 32, false, "FLOAT 兼容"));

        // ==================== 字符串类型 ====================
        sqlserverRules.add(new TypeMappingRule("VARCHAR2\\((\\d+)\\)", "NVARCHAR($1)", 40, false, "VARCHAR2 -> NVARCHAR"));
        sqlserverRules.add(new TypeMappingRule("NVARCHAR2\\((\\d+)\\)", "NVARCHAR($1)", 41, false, "NVARCHAR2 -> NVARCHAR"));
        sqlserverRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 42, false, "CHAR 兼容"));
        sqlserverRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "NCHAR($1)", 43, false, "NCHAR 兼容"));
        sqlserverRules.add(new TypeMappingRule("CLOB", "NVARCHAR(MAX)", 44, false, "CLOB -> NVARCHAR(MAX)"));
        sqlserverRules.add(new TypeMappingRule("NCLOB", "NVARCHAR(MAX)", 45, false, "NCLOB -> NVARCHAR(MAX)"));
        sqlserverRules.add(new TypeMappingRule("LONG", "NVARCHAR(MAX)", 46, false, "LONG -> NVARCHAR(MAX)"));

        // ==================== 二进制类型 ====================
        sqlserverRules.add(new TypeMappingRule("BLOB", "VARBINARY(MAX)", 50, false, "BLOB -> VARBINARY(MAX)"));
        sqlserverRules.add(new TypeMappingRule("RAW\\((\\d+)\\)", "VARBINARY($1)", 51, false, "RAW -> VARBINARY"));
        sqlserverRules.add(new TypeMappingRule("LONG RAW", "VARBINARY(MAX)", 52, false, "LONG RAW -> VARBINARY(MAX)"));

        // ==================== 日期时间类型 ====================
        sqlserverRules.add(new TypeMappingRule("DATE", "DATETIME2", 60, false, "DATE -> DATETIME2"));
        sqlserverRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH TIME ZONE", "DATETIMEOFFSET", 61, false, "TIMESTAMP WITH TZ -> DATETIMEOFFSET"));
        sqlserverRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "DATETIME2", 62, false, "TIMESTAMP -> DATETIME2"));

        // ==================== 其他类型 ====================
        sqlserverRules.add(new TypeMappingRule("XMLTYPE", "XML", 70, false, "XMLTYPE -> XML"));

        // ==================== 通配符兜底规则 ====================
        sqlserverRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        sqlserverRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("SQLSERVER", sqlserverRules);
        log.info("Initialized {} Oracle->SQL Server type mapping rules", sqlserverRules.size());
    }

    // ==================== Oracle -> StarRocks ====================

    private void initializeStarRocksMappings() {
        List<TypeMappingRule> starrocksRules = new ArrayList<>();

        // ==================== 数值类型 ====================
        starrocksRules.add(new TypeMappingRule("NUMBER\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 10, false, "NUMBER(p,s) -> DECIMAL(p,s)"));
        starrocksRules.add(new TypeMappingRule("NUMBER\\([1-2]\\)", "TINYINT", 20, false, "NUMBER(1-2) -> TINYINT"));
        starrocksRules.add(new TypeMappingRule("NUMBER\\([3-4]\\)", "SMALLINT", 21, false, "NUMBER(3-4) -> SMALLINT"));
        starrocksRules.add(new TypeMappingRule("NUMBER\\([5-9]\\)", "INT", 22, false, "NUMBER(5-9) -> INT"));
        starrocksRules.add(new TypeMappingRule("NUMBER\\(1[0-8]\\)", "BIGINT", 23, false, "NUMBER(10-18) -> BIGINT"));
        starrocksRules.add(new TypeMappingRule("NUMBER\\((\\d{2,})\\)", "DECIMAL($1,0)", 24, false, "NUMBER(19+) -> DECIMAL(p,0)"));
        starrocksRules.add(new TypeMappingRule("NUMBER", "DECIMAL(38,10)", 25, false, "NUMBER -> DECIMAL(38,10)"));

        // ==================== 浮点类型 ====================
        starrocksRules.add(new TypeMappingRule("BINARY_FLOAT", "FLOAT", 30, false, "BINARY_FLOAT -> FLOAT"));
        starrocksRules.add(new TypeMappingRule("BINARY_DOUBLE", "DOUBLE", 31, false, "BINARY_DOUBLE -> DOUBLE"));
        starrocksRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+\\))?", "DOUBLE", 32, false, "FLOAT -> DOUBLE"));

        // ==================== 字符串类型 ====================
        // StarRocks 的 CHAR(n)/VARCHAR(n) 按字节计数，中文等多字节字符（UTF-8 3字节/字符）会溢出
        // 统一映射为 STRING，避免 "String too long" 错误
        starrocksRules.add(new TypeMappingRule("VARCHAR2\\((\\d+)\\)", "STRING", 40, false, "VARCHAR2 -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("NVARCHAR2\\((\\d+)\\)", "STRING", 41, false, "NVARCHAR2 -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "STRING", 42, false, "CHAR -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("NCHAR\\((\\d+)\\)", "STRING", 43, false, "NCHAR -> STRING (Unicode 兼容)"));
        starrocksRules.add(new TypeMappingRule("CLOB", "STRING", 44, false, "CLOB -> STRING"));
        starrocksRules.add(new TypeMappingRule("NCLOB", "STRING", 45, false, "NCLOB -> STRING"));
        starrocksRules.add(new TypeMappingRule("LONG", "STRING", 46, false, "LONG -> STRING"));

        // ==================== 二进制类型 ====================
        starrocksRules.add(new TypeMappingRule("BLOB", "STRING", 50, true, "BLOB -> STRING (有损)"));
        starrocksRules.add(new TypeMappingRule("RAW\\((\\d+)\\)", "STRING", 51, true, "RAW -> STRING (有损)"));
        starrocksRules.add(new TypeMappingRule("LONG RAW", "STRING", 52, true, "LONG RAW -> STRING (有损)"));

        // ==================== 日期时间类型 ====================
        starrocksRules.add(new TypeMappingRule("DATE", "DATE", 60, false, "DATE 兼容"));
        starrocksRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH TIME ZONE", "DATETIME", 61, true, "TIMESTAMP WITH TZ -> DATETIME (丢失时区)"));
        starrocksRules.add(new TypeMappingRule("TIMESTAMP\\(\\d+\\) WITH LOCAL TIME ZONE", "DATETIME", 62, true, "TIMESTAMP WITH LOCAL TZ -> DATETIME (丢失时区)"));
        starrocksRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "DATETIME", 63, false, "TIMESTAMP -> DATETIME"));

        // ==================== 通配符兜底规则 ====================
        starrocksRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        starrocksRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("STARROCKS", starrocksRules);
        log.info("Initialized {} Oracle->StarRocks type mapping rules", starrocksRules.size());
    }
}
