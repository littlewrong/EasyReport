package cn.easyreport.sync.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * StarRocks 类型映射器
 *
 * 负责将 StarRocks 数据类型映射到其他数据库系统
 *
 * 支持的目标数据库：
 * - MYSQL: MySQL 数据库
 * - TIDB: TiDB 分布式数据库
 * - STARROCKS: StarRocks 同源映射
 */
public class StarRocksTypeMapper implements DatabaseTypeMapper {

    private static final Logger log = LoggerFactory.getLogger(StarRocksTypeMapper.class);

    /** 源数据库类型 */
    private static final String SOURCE_DB_TYPE = "STARROCKS";

    /** 映射规则集合：目标数据库 -> 规则列表 */
    private final Map<String, List<TypeMappingRule>> mappingRules = new HashMap<>();

    /**
     * 构造函数，初始化所有映射规则
     */
    public StarRocksTypeMapper() {
        initializeStarRocksMappings(); // 同源映射
        initializeMySqlMappings(); // StarRocks -> MySQL
        initializeTiDbMappings(); // StarRocks -> TiDB
        initializeSqlServerMappings(); // StarRocks -> SQL Server
        initializePostgreSqlMappings(); // StarRocks -> PostgreSQL
        initializeOracleMappings(); // StarRocks -> Oracle
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

        // 按优先级顺序匹配规则
        for (TypeMappingRule rule : rules) {
            if (rule.matches(sourceType)) {
                String result = rule.apply(sourceType);
                log.debug("Mapped StarRocks type '{}' to {} type '{}' using rule: {}",
                        sourceType, targetDbType, result, rule.getSourcePattern());
                return result;
            }
        }

        // 未找到匹配规则，返回原类型
        log.debug("No matching rule for StarRocks type '{}' to {}, keeping original", sourceType, targetDbType);
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

        // 查找匹配的规则并检查是否有损
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

    /**
     * 初始化 StarRocks -> StarRocks 的类型映射规则（同源映射）
     */
    private void initializeStarRocksMappings() {
        List<TypeMappingRule> starrocksRules = new ArrayList<>();

        // 所有类型保持原样
        starrocksRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "StarRocks同源映射，保持原样"));

        mappingRules.put("STARROCKS", starrocksRules);
        log.info("Initialized {} StarRocks->StarRocks type mapping rules", starrocksRules.size());
    }

    /**
     * 初始化 StarRocks -> MySQL 的类型映射规则
     *
     * StarRocks 到 MySQL 的主要差异：
     * 1. STRING 类型需要转换为 TEXT 或 VARCHAR
     * 2. 大部分类型兼容
     */
    private void initializeMySqlMappings() {
        List<TypeMappingRule> mysqlRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        mysqlRules.add(new TypeMappingRule("TINYINT", "TINYINT", 10, false,
                "TINYINT 兼容"));
        mysqlRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 11, false,
                "SMALLINT 兼容"));
        mysqlRules.add(new TypeMappingRule("INT", "INT", 12, false,
                "INT 兼容"));
        mysqlRules.add(new TypeMappingRule("BIGINT", "BIGINT", 13, false,
                "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        mysqlRules.add(new TypeMappingRule("FLOAT", "FLOAT", 20, false,
                "FLOAT 兼容"));
        mysqlRules.add(new TypeMappingRule("DOUBLE", "DOUBLE", 21, false,
                "DOUBLE 兼容"));

        // ==================== 精确数值类型 ====================
        mysqlRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false,
                "DECIMAL 兼容"));

        // ==================== 字符串类型 ====================
        mysqlRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false,
                "CHAR 兼容"));
        // VARCHAR(>=10000) 超出 MySQL 限制（utf8mb4 最大 16383），转为 TEXT
        mysqlRules.add(new TypeMappingRule("VARCHAR\\(\\d{5,}\\)", "TEXT", 41, false,
                "VARCHAR(>=10000) -> TEXT，超出MySQL VARCHAR长度限制"));
        mysqlRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 42, false,
                "VARCHAR 兼容"));

        // STRING 转换为 TEXT (MySQL)
        mysqlRules.add(new TypeMappingRule("STRING", "TEXT", 42, false,
                "STRING -> TEXT"));

        // ==================== 日期时间类型 ====================
        mysqlRules.add(new TypeMappingRule("DATE", "DATE", 50, false,
                "DATE 兼容"));
        mysqlRules.add(new TypeMappingRule("DATETIME", "DATETIME", 51, false,
                "DATETIME 兼容"));

        // ==================== JSON 类型 ====================
        mysqlRules.add(new TypeMappingRule("JSON", "JSON", 60, false,
                "JSON 兼容"));

        // ==================== BOOLEAN 类型 ====================
        mysqlRules.add(new TypeMappingRule("BOOLEAN", "TINYINT(1)", 70, false,
                "BOOLEAN -> TINYINT(1)"));

        // ==================== 通配符兜底规则 ====================
        mysqlRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "未匹配类型保持原样"));

        mysqlRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));

        mappingRules.put("MYSQL", mysqlRules);
        log.info("Initialized {} StarRocks->MySQL type mapping rules", mysqlRules.size());
    }

    /**
     * 初始化 StarRocks -> TiDB 的类型映射规则
     *
     * TiDB 是 MySQL 兼容的分布式数据库，独立定义映射规则
     */
    private void initializeTiDbMappings() {
        List<TypeMappingRule> tidbRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        tidbRules.add(new TypeMappingRule("TINYINT", "TINYINT", 10, false,
                "TINYINT 兼容"));
        tidbRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 11, false,
                "SMALLINT 兼容"));
        tidbRules.add(new TypeMappingRule("INT", "INT", 12, false,
                "INT 兼容"));
        tidbRules.add(new TypeMappingRule("BIGINT", "BIGINT", 13, false,
                "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        tidbRules.add(new TypeMappingRule("FLOAT", "FLOAT", 20, false,
                "FLOAT 兼容"));
        tidbRules.add(new TypeMappingRule("DOUBLE", "DOUBLE", 21, false,
                "DOUBLE 兼容"));

        // ==================== 精确数值类型 ====================
        tidbRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false,
                "DECIMAL 兼容"));

        // ==================== 字符串类型 ====================
        tidbRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false,
                "CHAR 兼容"));
        // VARCHAR(>=10000) 超出 TiDB/MySQL 限制，转为 TEXT
        tidbRules.add(new TypeMappingRule("VARCHAR\\(\\d{5,}\\)", "TEXT", 41, false,
                "VARCHAR(>=10000) -> TEXT，超出VARCHAR长度限制"));
        tidbRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 42, false,
                "VARCHAR 兼容"));
        tidbRules.add(new TypeMappingRule("STRING", "TEXT", 43, false,
                "STRING -> TEXT"));

        // ==================== 日期时间类型 ====================
        tidbRules.add(new TypeMappingRule("DATE", "DATE", 50, false,
                "DATE 兼容"));
        tidbRules.add(new TypeMappingRule("DATETIME", "DATETIME", 51, false,
                "DATETIME 兼容"));

        // ==================== JSON 类型 ====================
        tidbRules.add(new TypeMappingRule("JSON", "JSON", 60, false,
                "JSON 兼容"));

        // ==================== BOOLEAN 类型 ====================
        tidbRules.add(new TypeMappingRule("BOOLEAN", "TINYINT(1)", 70, false,
                "BOOLEAN -> TINYINT(1)"));

        // ==================== 通配符兜底规则 ====================
        tidbRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "未匹配类型保持原样"));

        tidbRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("TIDB", tidbRules);
        log.info("Initialized {} StarRocks->TiDB type mapping rules", tidbRules.size());
    }

    /**
     * 初始化 StarRocks -> SQL Server 的类型映射规则
     *
     * StarRocks 与 SQL Server 的主要差异：
     * 1. STRING 类型需要转换为 VARCHAR(MAX)
     * 2. BOOLEAN 转换为 BIT
     * 3. DATETIME 转换为 DATETIME2
     * 4. StarRocks 的 JSON 类型转换为 NVARCHAR(MAX)
     */
    private void initializeSqlServerMappings() {
        List<TypeMappingRule> sqlserverRules = new ArrayList<>();

        // ==================== 整数类型（去除显示宽度，SQL Server 不支持） ====================
        // SQL Server TINYINT 是无符号 0-255，StarRocks TINYINT 是有符号 -128-127，
        // 负值会溢出，因此映射为 SMALLINT（有符号 16-bit）
        sqlserverRules.add(new TypeMappingRule("TINYINT\\(\\d+\\)", "SMALLINT", 10, false,
                "TINYINT(n) -> SMALLINT，SQL Server TINYINT 无符号不支持负值"));
        sqlserverRules.add(new TypeMappingRule("TINYINT", "SMALLINT", 11, false,
                "TINYINT -> SMALLINT，SQL Server TINYINT 无符号不支持负值"));
        sqlserverRules.add(new TypeMappingRule("SMALLINT\\(\\d+\\)", "SMALLINT", 12, false,
                "SMALLINT(n) -> SMALLINT，去除显示宽度"));
        sqlserverRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 13, false,
                "SMALLINT 兼容"));
        sqlserverRules.add(new TypeMappingRule("INT\\(\\d+\\)", "INT", 14, false,
                "INT(n) -> INT，去除显示宽度"));
        sqlserverRules.add(new TypeMappingRule("INT", "INT", 15, false,
                "INT 兼容"));
        sqlserverRules.add(new TypeMappingRule("BIGINT\\(\\d+\\)", "BIGINT", 16, false,
                "BIGINT(n) -> BIGINT，去除显示宽度"));
        sqlserverRules.add(new TypeMappingRule("BIGINT", "BIGINT", 17, false,
                "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        sqlserverRules.add(new TypeMappingRule("FLOAT", "FLOAT", 20, false,
                "FLOAT 兼容"));
        sqlserverRules.add(new TypeMappingRule("DOUBLE", "FLOAT", 21, false,
                "DOUBLE -> FLOAT"));

        // ==================== 精确数值类型 ====================
        sqlserverRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "DECIMAL($1,$2)", 30, false,
                "DECIMAL 兼容"));

        // ==================== 字符串类型 ====================
        sqlserverRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false,
                "CHAR 兼容"));
        sqlserverRules.add(new TypeMappingRule("VARCHAR\\(([1-9]\\d{0,2}|[1-3]\\d{3}|40[0-9][0-9]|4000)\\)", "VARCHAR($1)", 41, false,
                "VARCHAR(<=4000) 兼容"));
        sqlserverRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR(MAX)", 42, false,
                "VARCHAR(>4000) -> VARCHAR(MAX)"));

        // STRING 转换为 VARCHAR(MAX)
        sqlserverRules.add(new TypeMappingRule("STRING", "VARCHAR(MAX)", 43, false,
                "STRING -> VARCHAR(MAX)"));

        // ==================== 日期时间类型 ====================
        sqlserverRules.add(new TypeMappingRule("DATE", "DATE", 50, false,
                "DATE 兼容"));
        sqlserverRules.add(new TypeMappingRule("DATETIME", "DATETIME2", 51, false,
                "DATETIME -> DATETIME2"));

        // ==================== JSON 类型 ====================
        sqlserverRules.add(new TypeMappingRule("JSON", "NVARCHAR(MAX)", 60, false,
                "JSON -> NVARCHAR(MAX)"));

        // ==================== BOOLEAN 类型 ====================
        sqlserverRules.add(new TypeMappingRule("BOOLEAN", "BIT", 70, false,
                "BOOLEAN -> BIT"));

        // ==================== 通配符兜底规则 ====================
        sqlserverRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "未匹配类型保持原样"));

        sqlserverRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));

        mappingRules.put("SQLSERVER", sqlserverRules);
        log.info("Initialized {} StarRocks->SQL Server type mapping rules", sqlserverRules.size());
    }

    /**
     * 初始化 StarRocks -> PostgreSQL 的类型映射规则
     *
     * PostgreSQL 与 StarRocks/MySQL 的主要差异：
     * 1. 整数类型不支持显示宽度，如 bigint(20) → BIGINT
     * 2. DATETIME 不存在，使用 TIMESTAMP
     * 3. DOUBLE 使用 DOUBLE PRECISION
     * 4. DECIMAL 使用 NUMERIC
     */
    private void initializePostgreSqlMappings() {
        List<TypeMappingRule> pgRules = new ArrayList<>();

        // ==================== 整数类型（去除显示宽度） ====================
        pgRules.add(new TypeMappingRule("TINYINT\\(\\d+\\)", "SMALLINT", 10, false,
                "TINYINT(n) -> SMALLINT，去除显示宽度"));
        pgRules.add(new TypeMappingRule("TINYINT", "SMALLINT", 11, false,
                "TINYINT -> SMALLINT"));
        pgRules.add(new TypeMappingRule("SMALLINT\\(\\d+\\)", "SMALLINT", 12, false,
                "SMALLINT(n) -> SMALLINT，去除显示宽度"));
        pgRules.add(new TypeMappingRule("SMALLINT", "SMALLINT", 13, false,
                "SMALLINT 兼容"));
        pgRules.add(new TypeMappingRule("INT\\(\\d+\\)", "INTEGER", 14, false,
                "INT(n) -> INTEGER，去除显示宽度"));
        pgRules.add(new TypeMappingRule("INT", "INTEGER", 15, false,
                "INT -> INTEGER"));
        pgRules.add(new TypeMappingRule("BIGINT\\(\\d+\\)", "BIGINT", 16, false,
                "BIGINT(n) -> BIGINT，去除显示宽度"));
        pgRules.add(new TypeMappingRule("BIGINT", "BIGINT", 17, false,
                "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        pgRules.add(new TypeMappingRule("FLOAT", "REAL", 20, false,
                "FLOAT -> REAL"));
        pgRules.add(new TypeMappingRule("DOUBLE", "DOUBLE PRECISION", 21, false,
                "DOUBLE -> DOUBLE PRECISION"));

        // ==================== 精确数值类型 ====================
        pgRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "NUMERIC($1,$2)", 30, false,
                "DECIMAL -> NUMERIC"));

        // ==================== 字符串类型 ====================
        pgRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false,
                "CHAR 兼容"));
        pgRules.add(new TypeMappingRule("VARCHAR\\(\\d{5,}\\)", "TEXT", 41, false,
                "VARCHAR(>=10000) -> TEXT，超大VARCHAR转TEXT"));
        pgRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 42, false,
                "VARCHAR 兼容"));
        pgRules.add(new TypeMappingRule("STRING", "TEXT", 43, false,
                "STRING -> TEXT"));

        // ==================== 日期时间类型 ====================
        pgRules.add(new TypeMappingRule("DATE", "DATE", 50, false,
                "DATE 兼容"));
        pgRules.add(new TypeMappingRule("DATETIME", "TIMESTAMP", 51, false,
                "DATETIME -> TIMESTAMP"));

        // ==================== JSON 类型 ====================
        pgRules.add(new TypeMappingRule("JSON", "JSONB", 60, false,
                "JSON -> JSONB"));

        // ==================== BOOLEAN 类型 ====================
        pgRules.add(new TypeMappingRule("BOOLEAN", "BOOLEAN", 70, false,
                "BOOLEAN 兼容"));

        // ==================== 通配符兜底规则 ====================
        pgRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "未匹配类型保持原样"));

        pgRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("POSTGRESQL", pgRules);
        log.info("Initialized {} StarRocks->PostgreSQL type mapping rules", pgRules.size());
    }

    /**
     * 初始化 StarRocks -> Oracle 的类型映射规则
     */
    private void initializeOracleMappings() {
        List<TypeMappingRule> oracleRules = new ArrayList<>();

        // ==================== 整数类型（去除显示宽度） ====================
        oracleRules.add(new TypeMappingRule("TINYINT\\(\\d+\\)", "NUMBER(3)", 10, false, "TINYINT(n) -> NUMBER(3)"));
        oracleRules.add(new TypeMappingRule("TINYINT", "NUMBER(3)", 11, false, "TINYINT -> NUMBER(3)"));
        oracleRules.add(new TypeMappingRule("SMALLINT\\(\\d+\\)", "NUMBER(5)", 12, false, "SMALLINT(n) -> NUMBER(5)"));
        oracleRules.add(new TypeMappingRule("SMALLINT", "NUMBER(5)", 13, false, "SMALLINT -> NUMBER(5)"));
        oracleRules.add(new TypeMappingRule("INT\\(\\d+\\)", "NUMBER(10)", 14, false, "INT(n) -> NUMBER(10)"));
        oracleRules.add(new TypeMappingRule("INT", "NUMBER(10)", 15, false, "INT -> NUMBER(10)"));
        oracleRules.add(new TypeMappingRule("BIGINT\\(\\d+\\)", "NUMBER(19)", 16, false, "BIGINT(n) -> NUMBER(19)"));
        oracleRules.add(new TypeMappingRule("BIGINT", "NUMBER(19)", 17, false, "BIGINT -> NUMBER(19)"));

        // ==================== 浮点类型 ====================
        oracleRules.add(new TypeMappingRule("FLOAT", "BINARY_FLOAT", 20, false, "FLOAT -> BINARY_FLOAT"));
        oracleRules.add(new TypeMappingRule("DOUBLE", "BINARY_DOUBLE", 21, false, "DOUBLE -> BINARY_DOUBLE"));

        // ==================== 精确数值类型 ====================
        oracleRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)", "NUMBER($1,$2)", 30, false, "DECIMAL -> NUMBER(p,s)"));

        // ==================== 字符串类型 ====================
        oracleRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 40, false, "CHAR 兼容"));
        // VARCHAR2 最大 4000 字节，超大 VARCHAR 转 CLOB
        oracleRules.add(new TypeMappingRule("VARCHAR\\(([1-3]\\d{3}|[1-9]\\d{0,2})\\)", "VARCHAR2($1)", 41, false, "VARCHAR(<=3999) -> VARCHAR2"));
        oracleRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "CLOB", 42, false, "VARCHAR(>=4000) -> CLOB"));
        oracleRules.add(new TypeMappingRule("STRING", "CLOB", 43, false, "STRING -> CLOB"));

        // ==================== 日期时间类型 ====================
        oracleRules.add(new TypeMappingRule("DATE", "DATE", 50, false, "DATE 兼容"));
        oracleRules.add(new TypeMappingRule("DATETIME", "TIMESTAMP", 51, false, "DATETIME -> TIMESTAMP"));

        // ==================== JSON 类型 ====================
        oracleRules.add(new TypeMappingRule("JSON", "CLOB", 60, false, "JSON -> CLOB"));

        // ==================== BOOLEAN 类型 ====================
        oracleRules.add(new TypeMappingRule("BOOLEAN", "NUMBER(1)", 70, false, "BOOLEAN -> NUMBER(1)"));

        // ==================== 通配符兜底规则 ====================
        oracleRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        oracleRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("ORACLE", oracleRules);
        log.info("Initialized {} StarRocks->Oracle type mapping rules", oracleRules.size());
    }
}
