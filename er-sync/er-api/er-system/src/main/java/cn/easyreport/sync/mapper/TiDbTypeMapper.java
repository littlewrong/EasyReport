package cn.easyreport.sync.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * TiDB 类型映射器
 *
 * 负责将 TiDB 数据类型映射到其他数据库系统
 *
 * 支持的目标数据库：
 * - MYSQL: MySQL 标准数据库
 * - (未来可扩展) STARROCKS, CLICKHOUSE 等
 */
public class TiDbTypeMapper implements DatabaseTypeMapper {

    private static final Logger log = LoggerFactory.getLogger(TiDbTypeMapper.class);

    /** 源数据库类型 */
    private static final String SOURCE_DB_TYPE = "TIDB";

    /** 映射规则集合：目标数据库 -> 规则列表 */
    private final Map<String, List<TypeMappingRule>> mappingRules = new HashMap<>();

    /**
     * 构造函数，初始化所有映射规则
     */
    public TiDbTypeMapper() {
        initializeTiDbMappings(); // 同源映射
        initializeMySqlMappings();
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

        // 按优先级顺序匹配规则
        for (TypeMappingRule rule : rules) {
            if (rule.matches(sourceType)) {
                String result = rule.apply(sourceType);
                log.debug("Mapped TiDB type '{}' to {} type '{}' using rule: {}",
                        sourceType, targetDbType, result, rule.getSourcePattern());
                return result;
            }
        }

        // 未找到匹配规则，返回原类型
        log.debug("No matching rule for TiDB type '{}' to {}, keeping original", sourceType, targetDbType);
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
     * 初始化 TiDB -> TiDB 的类型映射规则（同源映射）
     *
     * 当源和目标都是 TiDB 时，所有类型保持原样
     * 这样做的好处：
     * 1. 行为明确，日志清晰
     * 2. 未来可以在此基础上做优化
     */
    private void initializeTiDbMappings() {
        List<TypeMappingRule> tidbRules = new ArrayList<>();

        // 所有类型保持原样（使用通配符匹配）
        // 优先级设为 999，确保在特殊规则之后匹配
        tidbRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "TiDB同源映射，保持原样"));

        mappingRules.put("TIDB", tidbRules);
        log.info("Initialized {} TiDB->TiDB type mapping rules", tidbRules.size());
    }

    /**
     * 初始化 TiDB -> MySQL 的类型映射规则
     *
     * 由于 TiDB 是 MySQL 兼容的数据库，大部分类型可以直接映射
     * 主要注意：
     * - TiDB 特有的 AUTO_RANDOM 在 MySQL 中不支持，需要转为 AUTO_INCREMENT
     * - 其他类型基本完全兼容
     */
    private void initializeMySqlMappings() {
        List<TypeMappingRule> mysqlRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        // 完全兼容，直接保持
        mysqlRules.add(new TypeMappingRule("TINYINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "$0", 10, false,
                "TINYINT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("SMALLINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "$0", 11, false,
                "SMALLINT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("MEDIUMINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "$0", 12, false,
                "MEDIUMINT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("INT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "$0", 13, false,
                "INT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("INTEGER(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "$0", 14, false,
                "INTEGER 完全兼容"));
        mysqlRules.add(new TypeMappingRule("BIGINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "$0", 15, false,
                "BIGINT 完全兼容"));

        // ==================== 浮点和定点类型 ====================
        mysqlRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "$0", 20, false,
                "FLOAT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("DOUBLE(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "$0", 21, false,
                "DOUBLE 完全兼容"));
        mysqlRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "DECIMAL($1,$2)", 22, false,
                "DECIMAL 完全兼容"));
        mysqlRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "NUMERIC($1,$2)", 23, false,
                "NUMERIC 完全兼容"));

        // ==================== 字符串类型 ====================
        mysqlRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 30, false,
                "CHAR 完全兼容"));
        mysqlRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 31, false,
                "VARCHAR 完全兼容"));
        mysqlRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "BINARY($1)", 32, false,
                "BINARY 完全兼容"));
        mysqlRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "VARBINARY($1)", 33, false,
                "VARBINARY 完全兼容"));

        // TEXT 类型
        mysqlRules.add(new TypeMappingRule("TINYTEXT", "TINYTEXT", 40, false,
                "TINYTEXT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("TEXT", "TEXT", 41, false,
                "TEXT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("MEDIUMTEXT", "MEDIUMTEXT", 42, false,
                "MEDIUMTEXT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("LONGTEXT", "LONGTEXT", 43, false,
                "LONGTEXT 完全兼容"));

        // BLOB 类型
        mysqlRules.add(new TypeMappingRule("TINYBLOB", "TINYBLOB", 50, false,
                "TINYBLOB 完全兼容"));
        mysqlRules.add(new TypeMappingRule("BLOB", "BLOB", 51, false,
                "BLOB 完全兼容"));
        mysqlRules.add(new TypeMappingRule("MEDIUMBLOB", "MEDIUMBLOB", 52, false,
                "MEDIUMBLOB 完全兼容"));
        mysqlRules.add(new TypeMappingRule("LONGBLOB", "LONGBLOB", 53, false,
                "LONGBLOB 完全兼容"));

        // ==================== 日期时间类型 ====================
        mysqlRules.add(new TypeMappingRule("DATE", "DATE", 60, false,
                "DATE 完全兼容"));
        mysqlRules.add(new TypeMappingRule("TIME(?:\\(\\d+\\))?", "$0", 61, false,
                "TIME 完全兼容"));
        mysqlRules.add(new TypeMappingRule("DATETIME(?:\\(\\d+\\))?", "$0", 62, false,
                "DATETIME 完全兼容"));
        mysqlRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "$0", 63, false,
                "TIMESTAMP 完全兼容"));
        mysqlRules.add(new TypeMappingRule("YEAR(?:\\(\\d+\\))?", "YEAR", 64, false,
                "YEAR 完全兼容"));

        // ==================== 枚举和集合类型 ====================
        mysqlRules.add(new TypeMappingRule("ENUM\\(.*?\\)", "$0", 70, false,
                "ENUM 完全兼容"));
        mysqlRules.add(new TypeMappingRule("SET\\(.*?\\)", "$0", 71, false,
                "SET 完全兼容"));

        // ==================== JSON 类型 ====================
        mysqlRules.add(new TypeMappingRule("JSON", "JSON", 80, false,
                "JSON 完全兼容"));

        // ==================== 空间数据类型 ====================
        mysqlRules.add(new TypeMappingRule("GEOMETRY", "GEOMETRY", 90, false,
                "GEOMETRY 完全兼容"));
        mysqlRules.add(new TypeMappingRule("POINT", "POINT", 91, false,
                "POINT 完全兼容"));
        mysqlRules.add(new TypeMappingRule("LINESTRING", "LINESTRING", 92, false,
                "LINESTRING 完全兼容"));
        mysqlRules.add(new TypeMappingRule("POLYGON", "POLYGON", 93, false,
                "POLYGON 完全兼容"));

        // 按优先级排序
        mysqlRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));

        mappingRules.put("MYSQL", mysqlRules);
        log.info("Initialized {} TiDB->MySQL type mapping rules", mysqlRules.size());
    }

    /**
     * 初始化 TiDB -> StarRocks 的类型映射规则
     *
     * TiDB 与 MySQL 高度兼容，映射规则与 MySQL -> StarRocks 基本一致
     */
    private void initializeStarRocksMappings() {
        List<TypeMappingRule> starrocksRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        starrocksRules.add(new TypeMappingRule("TINYINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "TINYINT", 10, false,
                "TINYINT 兼容"));
        starrocksRules.add(new TypeMappingRule("SMALLINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "SMALLINT", 11, false,
                "SMALLINT 兼容"));
        starrocksRules.add(new TypeMappingRule("MEDIUMINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "INT", 12, false,
                "MEDIUMINT -> INT"));
        starrocksRules.add(new TypeMappingRule("INT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "INT", 13, false,
                "INT 兼容"));
        starrocksRules.add(new TypeMappingRule("INTEGER(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "INT", 14, false,
                "INTEGER -> INT"));
        starrocksRules.add(new TypeMappingRule("BIGINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "BIGINT", 15, false,
                "BIGINT 兼容"));

        // ==================== 浮点类型 ====================
        starrocksRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "FLOAT", 20, false,
                "FLOAT 兼容"));
        starrocksRules.add(new TypeMappingRule("DOUBLE(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "DOUBLE", 21, false,
                "DOUBLE 兼容"));
        starrocksRules.add(new TypeMappingRule("REAL(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "DOUBLE", 22, false,
                "REAL -> DOUBLE"));

        // ==================== 精确数值类型 ====================
        starrocksRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "DECIMAL($1,$2)", 30, false,
                "DECIMAL 兼容"));
        starrocksRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "DECIMAL($1,$2)", 31, false,
                "NUMERIC -> DECIMAL"));
        starrocksRules.add(new TypeMappingRule("DECIMAL(?:\\s+UNSIGNED)?", "DECIMAL(10,0)", 32, false,
                "DECIMAL 无参数 -> DECIMAL(10,0)"));

        // ==================== 字符串类型 ====================
        // StarRocks CHAR/VARCHAR 以字节计量，TiDB 以字符计量
        // UTF-8 中文字符占 3 字节，直接映射会导致截断，统一使用 STRING 避免问题
        starrocksRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "STRING", 40, false,
                "CHAR -> STRING (UTF-8字节安全)"));
        starrocksRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "STRING", 41, false,
                "VARCHAR -> STRING (UTF-8字节安全)"));

        // TEXT 系列转换为 STRING
        starrocksRules.add(new TypeMappingRule("TINYTEXT", "STRING", 42, false,
                "TINYTEXT -> STRING"));
        starrocksRules.add(new TypeMappingRule("TEXT", "STRING", 43, false,
                "TEXT -> STRING"));
        starrocksRules.add(new TypeMappingRule("MEDIUMTEXT", "STRING", 44, false,
                "MEDIUMTEXT -> STRING"));
        starrocksRules.add(new TypeMappingRule("LONGTEXT", "STRING", 45, false,
                "LONGTEXT -> STRING"));

        // BLOB 系列转换为 STRING
        starrocksRules.add(new TypeMappingRule("TINYBLOB", "STRING", 46, true,
                "TINYBLOB -> STRING (有损转换)"));
        starrocksRules.add(new TypeMappingRule("BLOB", "STRING", 47, true,
                "BLOB -> STRING (有损转换)"));
        starrocksRules.add(new TypeMappingRule("MEDIUMBLOB", "STRING", 48, true,
                "MEDIUMBLOB -> STRING (有损转换)"));
        starrocksRules.add(new TypeMappingRule("LONGBLOB", "STRING", 49, true,
                "LONGBLOB -> STRING (有损转换)"));

        // BINARY 和 VARBINARY 转换为 STRING
        starrocksRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "STRING", 50, true,
                "BINARY -> STRING (有损转换)"));
        starrocksRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "STRING", 51, true,
                "VARBINARY -> STRING (有损转换)"));

        // ==================== 日期时间类型 ====================
        starrocksRules.add(new TypeMappingRule("DATE", "DATE", 60, false,
                "DATE 兼容"));
        starrocksRules.add(new TypeMappingRule("DATETIME(?:\\(\\d+\\))?", "DATETIME", 61, false,
                "DATETIME 兼容"));
        starrocksRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "DATETIME", 62, false,
                "TIMESTAMP -> DATETIME"));
        starrocksRules.add(new TypeMappingRule("TIME(?:\\(\\d+\\))?", "STRING", 63, true,
                "TIME -> STRING (StarRocks不支持TIME)"));
        starrocksRules.add(new TypeMappingRule("YEAR(?:\\(\\d+\\))?", "SMALLINT", 64, false,
                "YEAR -> SMALLINT"));

        // ==================== JSON 类型 ====================
        starrocksRules.add(new TypeMappingRule("JSON", "JSON", 70, false,
                "JSON 兼容"));

        // ==================== 特殊类型 ====================
        starrocksRules.add(new TypeMappingRule("ENUM\\(.*\\)", "VARCHAR(255)", 80, true,
                "ENUM -> VARCHAR(255) (有损转换)"));
        starrocksRules.add(new TypeMappingRule("SET\\(.*\\)", "VARCHAR(1024)", 81, true,
                "SET -> VARCHAR(1024) (有损转换)"));
        starrocksRules.add(new TypeMappingRule("BIT\\(1\\)", "BOOLEAN", 82, false,
                "BIT(1) -> BOOLEAN"));
        starrocksRules.add(new TypeMappingRule("BIT\\((\\d+)\\)", "INT", 83, false,
                "BIT(n) -> INT"));
        starrocksRules.add(new TypeMappingRule("BOOL(?:EAN)?", "BOOLEAN", 84, false,
                "BOOLEAN 兼容"));

        // ==================== 通配符兜底规则 ====================
        starrocksRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "未匹配类型保持原样"));

        // 按优先级排序
        starrocksRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));

        mappingRules.put("STARROCKS", starrocksRules);
        log.info("Initialized {} TiDB->StarRocks type mapping rules", starrocksRules.size());
    }

    /**
     * 初始化 TiDB -> SQL Server 的类型映射规则
     *
     * TiDB 是 MySQL 兼容的分布式数据库，类型映射规则与 MySQL -> SQL Server 基本相同
     */
    private void initializeSqlServerMappings() {
        List<TypeMappingRule> sqlserverRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        sqlserverRules.add(new TypeMappingRule("TINYINT\\(1\\)", "BIT", 10, false,
                "TINYINT(1) -> BIT (布尔值)"));
        sqlserverRules.add(new TypeMappingRule("TINYINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "SMALLINT", 11, false,
                "TINYINT -> SMALLINT (SS TINYINT 无符号 0-255，有符号需用 SMALLINT)"));
        // UNSIGNED 专用规则（防溢出，优先级低于通用规则先匹配）
        sqlserverRules.add(new TypeMappingRule("SMALLINT(?:\\(\\d+\\))?\\s+UNSIGNED", "INT", 5, false,
                "SMALLINT UNSIGNED -> INT (防溢出)"));
        sqlserverRules.add(new TypeMappingRule("INT(?:\\(\\d+\\))?\\s+UNSIGNED", "BIGINT", 6, false,
                "INT UNSIGNED -> BIGINT (防溢出)"));
        sqlserverRules.add(new TypeMappingRule("INTEGER(?:\\(\\d+\\))?\\s+UNSIGNED", "BIGINT", 6, false,
                "INTEGER UNSIGNED -> BIGINT (防溢出)"));
        sqlserverRules.add(new TypeMappingRule("BIGINT(?:\\(\\d+\\))?\\s+UNSIGNED", "DECIMAL(20,0)", 7, false,
                "BIGINT UNSIGNED -> DECIMAL(20,0) (防溢出)"));
        // 有符号通用规则
        sqlserverRules.add(new TypeMappingRule("SMALLINT(?:\\(\\d+\\))?", "SMALLINT", 12, false,
                "SMALLINT 兼容"));
        sqlserverRules.add(new TypeMappingRule("MEDIUMINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "INT", 13, false,
                "MEDIUMINT -> INT"));
        sqlserverRules.add(new TypeMappingRule("INT(?:\\(\\d+\\))?", "INT", 14, false,
                "INT 兼容"));
        sqlserverRules.add(new TypeMappingRule("INTEGER(?:\\(\\d+\\))?", "INT", 15, false,
                "INTEGER -> INT"));
        sqlserverRules.add(new TypeMappingRule("BIGINT(?:\\(\\d+\\))?", "BIGINT", 16, false,
                "BIGINT 兼容"));

        // ==================== 浮点和定点类型 ====================
        sqlserverRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "FLOAT", 20, false,
                "FLOAT 兼容"));
        sqlserverRules.add(new TypeMappingRule("DOUBLE(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "FLOAT", 21, false,
                "DOUBLE -> FLOAT"));
        sqlserverRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "DECIMAL($1,$2)", 22, false,
                "DECIMAL 兼容"));
        sqlserverRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "NUMERIC($1,$2)", 23, false,
                "NUMERIC 兼容"));

        // ==================== 字符串类型 ====================
        // 使用 NCHAR/NVARCHAR 确保 Unicode 兼容（中文等多字节字符不会截断）
        sqlserverRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "NCHAR($1)", 30, false,
                "CHAR -> NCHAR (Unicode 兼容)"));
        sqlserverRules.add(new TypeMappingRule("VARCHAR\\(([1-9]\\d{0,2}|[1-3]\\d{3}|40[0-9][0-9]|4000)\\)", "NVARCHAR($1)", 31, false,
                "VARCHAR(<=4000) -> NVARCHAR (Unicode 兼容)"));
        sqlserverRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "NVARCHAR(MAX)", 32, false,
                "VARCHAR(>4000) -> NVARCHAR(MAX)"));

        // TEXT 系列转换为 NVARCHAR (Unicode 兼容)
        sqlserverRules.add(new TypeMappingRule("TINYTEXT", "NVARCHAR(255)", 40, false,
                "TINYTEXT -> NVARCHAR(255)"));
        sqlserverRules.add(new TypeMappingRule("TEXT", "NVARCHAR(MAX)", 41, false,
                "TEXT -> NVARCHAR(MAX)"));
        sqlserverRules.add(new TypeMappingRule("MEDIUMTEXT", "NVARCHAR(MAX)", 42, false,
                "MEDIUMTEXT -> NVARCHAR(MAX)"));
        sqlserverRules.add(new TypeMappingRule("LONGTEXT", "NVARCHAR(MAX)", 43, false,
                "LONGTEXT -> NVARCHAR(MAX)"));

        // BINARY 和 VARBINARY
        sqlserverRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "BINARY($1)", 50, false,
                "BINARY 兼容"));
        sqlserverRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "VARBINARY($1)", 51, false,
                "VARBINARY 兼容"));

        // BLOB 系列转换为 VARBINARY(MAX)
        sqlserverRules.add(new TypeMappingRule("TINYBLOB", "VARBINARY(255)", 52, false,
                "TINYBLOB -> VARBINARY(255)"));
        sqlserverRules.add(new TypeMappingRule("BLOB", "VARBINARY(MAX)", 53, false,
                "BLOB -> VARBINARY(MAX)"));
        sqlserverRules.add(new TypeMappingRule("MEDIUMBLOB", "VARBINARY(MAX)", 54, false,
                "MEDIUMBLOB -> VARBINARY(MAX)"));
        sqlserverRules.add(new TypeMappingRule("LONGBLOB", "VARBINARY(MAX)", 55, false,
                "LONGBLOB -> VARBINARY(MAX)"));

        // ==================== 日期时间类型 ====================
        sqlserverRules.add(new TypeMappingRule("DATE", "DATE", 60, false,
                "DATE 兼容"));
        sqlserverRules.add(new TypeMappingRule("TIME(?:\\(\\d+\\))?", "TIME", 61, false,
                "TIME 兼容"));
        sqlserverRules.add(new TypeMappingRule("DATETIME(?:\\(\\d+\\))?", "DATETIME2", 62, false,
                "DATETIME -> DATETIME2"));
        sqlserverRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "DATETIME2", 63, false,
                "TIMESTAMP -> DATETIME2"));
        sqlserverRules.add(new TypeMappingRule("YEAR(?:\\(\\d+\\))?", "SMALLINT", 64, false,
                "YEAR -> SMALLINT"));

        // ==================== 枚举和集合类型 ====================
        sqlserverRules.add(new TypeMappingRule("ENUM\\(.*?\\)", "VARCHAR(255)", 70, true,
                "ENUM -> VARCHAR(255) (有损转换)"));
        sqlserverRules.add(new TypeMappingRule("SET\\(.*?\\)", "VARCHAR(1024)", 71, true,
                "SET -> VARCHAR(1024) (有损转换)"));

        // ==================== BIT 类型 ====================
        // SQL Server 的 BIT 不支持指定宽度，BIT(1) 必须去掉括号
        sqlserverRules.add(new TypeMappingRule("BIT\\(1\\)", "BIT", 75, false,
                "BIT(1) -> BIT (去除宽度)"));
        sqlserverRules.add(new TypeMappingRule("BIT\\((\\d+)\\)", "VARBINARY(8)", 76, false,
                "BIT(n) -> VARBINARY(8) (多位BIT)"));

        // ==================== JSON 类型 ====================
        sqlserverRules.add(new TypeMappingRule("JSON", "NVARCHAR(MAX)", 80, false,
                "JSON -> NVARCHAR(MAX)"));

        // ==================== 通配符兜底规则 ====================
        sqlserverRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "未匹配类型保持原样"));

        // 按优先级排序
        sqlserverRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));

        mappingRules.put("SQLSERVER", sqlserverRules);
        log.info("Initialized {} TiDB->SQL Server type mapping rules", sqlserverRules.size());
    }

    /**
     * 初始化 TiDB -> PostgreSQL 的类型映射规则
     *
     * TiDB 是 MySQL 兼容数据库，但 PostgreSQL 有不同的类型系统：
     * 1. 没有 TINYINT / MEDIUMINT
     * 2. DATETIME → TIMESTAMP
     * 3. DOUBLE → DOUBLE PRECISION
     * 4. 不支持 UNSIGNED / 显示宽度
     */
    private void initializePostgreSqlMappings() {
        List<TypeMappingRule> pgRules = new ArrayList<>();

        // ==================== 整数类型（去除显示宽度） ====================
        pgRules.add(new TypeMappingRule("TINYINT\\(1\\)", "BOOLEAN", 10, false,
                "TINYINT(1) -> BOOLEAN（布尔值）"));
        pgRules.add(new TypeMappingRule("TINYINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "SMALLINT", 11, false,
                "TINYINT -> SMALLINT"));
        pgRules.add(new TypeMappingRule("SMALLINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "SMALLINT", 12, false,
                "SMALLINT 兼容"));
        pgRules.add(new TypeMappingRule("MEDIUMINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "INTEGER", 13, false,
                "MEDIUMINT -> INTEGER"));
        pgRules.add(new TypeMappingRule("INT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "INTEGER", 14, false,
                "INT -> INTEGER"));
        pgRules.add(new TypeMappingRule("INTEGER(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "INTEGER", 15, false,
                "INTEGER 兼容"));
        pgRules.add(new TypeMappingRule("BIGINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "BIGINT", 16, false,
                "BIGINT 兼容"));

        // ==================== 浮点和定点类型 ====================
        pgRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "REAL", 20, false,
                "FLOAT -> REAL"));
        pgRules.add(new TypeMappingRule("DOUBLE(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "DOUBLE PRECISION", 21, false,
                "DOUBLE -> DOUBLE PRECISION"));
        pgRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "NUMERIC($1,$2)", 22, false,
                "DECIMAL -> NUMERIC"));
        pgRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "NUMERIC($1,$2)", 23, false,
                "NUMERIC 兼容"));

        // ==================== 字符串类型 ====================
        pgRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "CHAR($1)", 30, false,
                "CHAR 兼容"));
        pgRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "VARCHAR($1)", 31, false,
                "VARCHAR 兼容"));
        pgRules.add(new TypeMappingRule("TINYTEXT", "TEXT", 40, false, "TINYTEXT -> TEXT"));
        pgRules.add(new TypeMappingRule("TEXT", "TEXT", 41, false, "TEXT 兼容"));
        pgRules.add(new TypeMappingRule("MEDIUMTEXT", "TEXT", 42, false, "MEDIUMTEXT -> TEXT"));
        pgRules.add(new TypeMappingRule("LONGTEXT", "TEXT", 43, false, "LONGTEXT -> TEXT"));

        // BINARY / VARBINARY / BLOB -> BYTEA
        pgRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "BYTEA", 50, false, "BINARY -> BYTEA"));
        pgRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "BYTEA", 51, false, "VARBINARY -> BYTEA"));
        pgRules.add(new TypeMappingRule("TINYBLOB", "BYTEA", 52, false, "TINYBLOB -> BYTEA"));
        pgRules.add(new TypeMappingRule("BLOB", "BYTEA", 53, false, "BLOB -> BYTEA"));
        pgRules.add(new TypeMappingRule("MEDIUMBLOB", "BYTEA", 54, false, "MEDIUMBLOB -> BYTEA"));
        pgRules.add(new TypeMappingRule("LONGBLOB", "BYTEA", 55, false, "LONGBLOB -> BYTEA"));

        // ==================== 日期时间类型 ====================
        pgRules.add(new TypeMappingRule("DATE", "DATE", 60, false, "DATE 兼容"));
        pgRules.add(new TypeMappingRule("TIME(?:\\(\\d+\\))?", "TIME", 61, false, "TIME 兼容"));
        pgRules.add(new TypeMappingRule("DATETIME(?:\\(\\d+\\))?", "TIMESTAMP", 62, false, "DATETIME -> TIMESTAMP"));
        pgRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "TIMESTAMP", 63, false, "TIMESTAMP 兼容"));
        pgRules.add(new TypeMappingRule("YEAR(?:\\(\\d+\\))?", "SMALLINT", 64, false, "YEAR -> SMALLINT"));

        // ==================== 枚举和集合类型 ====================
        pgRules.add(new TypeMappingRule("ENUM\\(.*?\\)", "VARCHAR(255)", 70, true, "ENUM -> VARCHAR(255)（有损转换）"));
        pgRules.add(new TypeMappingRule("SET\\(.*?\\)", "VARCHAR(1024)", 71, true, "SET -> VARCHAR(1024)（有损转换）"));

        // ==================== BIT 类型 ====================
        pgRules.add(new TypeMappingRule("BIT\\(1\\)", "BOOLEAN", 75, false, "BIT(1) -> BOOLEAN"));
        pgRules.add(new TypeMappingRule("BIT\\((\\d+)\\)", "BIT($1)", 76, false, "BIT(n) 兼容"));

        // ==================== JSON 类型 ====================
        pgRules.add(new TypeMappingRule("JSON", "JSONB", 80, false, "JSON -> JSONB"));

        // ==================== BOOLEAN 类型 ====================
        pgRules.add(new TypeMappingRule("BOOL(?:EAN)?", "BOOLEAN", 84, false, "BOOLEAN 兼容"));

        // ==================== 通配符兜底规则 ====================
        pgRules.add(new TypeMappingRule(".*", "$0", 999, false, "未匹配类型保持原样"));

        pgRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("POSTGRESQL", pgRules);
        log.info("Initialized {} TiDB->PostgreSQL type mapping rules", pgRules.size());
    }

    /**
     * 初始化 TiDB -> Oracle 的类型映射规则
     * TiDB 与 MySQL 兼容，映射规则与 MySQL -> Oracle 基本相同
     */
    private void initializeOracleMappings() {
        List<TypeMappingRule> oracleRules = new ArrayList<>();

        // ==================== 整数类型 ====================
        oracleRules.add(new TypeMappingRule("TINYINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "NUMBER(3)", 10, false,
                "TINYINT -> NUMBER(3)"));
        oracleRules.add(new TypeMappingRule("SMALLINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "NUMBER(5)", 11, false,
                "SMALLINT -> NUMBER(5)"));
        oracleRules.add(new TypeMappingRule("MEDIUMINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "NUMBER(7)", 12, false,
                "MEDIUMINT -> NUMBER(7)"));
        oracleRules.add(new TypeMappingRule("INT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "NUMBER(10)", 13, false,
                "INT -> NUMBER(10)"));
        oracleRules.add(new TypeMappingRule("INTEGER(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "NUMBER(10)", 14, false,
                "INTEGER -> NUMBER(10)"));
        oracleRules.add(new TypeMappingRule("BIGINT(?:\\(\\d+\\))?(?:\\s+UNSIGNED)?", "NUMBER(19)", 15, false,
                "BIGINT -> NUMBER(19)"));

        // ==================== 浮点和定点类型 ====================
        oracleRules.add(new TypeMappingRule("FLOAT(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "BINARY_FLOAT", 20, false,
                "FLOAT -> BINARY_FLOAT"));
        oracleRules.add(new TypeMappingRule("DOUBLE(?:\\(\\d+,\\d+\\))?(?:\\s+UNSIGNED)?", "BINARY_DOUBLE", 21, false,
                "DOUBLE -> BINARY_DOUBLE"));
        oracleRules.add(new TypeMappingRule("DECIMAL\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "NUMBER($1,$2)", 22, false,
                "DECIMAL -> NUMBER(p,s)"));
        oracleRules.add(new TypeMappingRule("NUMERIC\\((\\d+),(\\d+)\\)(?:\\s+UNSIGNED)?", "NUMBER($1,$2)", 23, false,
                "NUMERIC -> NUMBER(p,s)"));

        // ==================== 字符串类型 ====================
        // 使用 NCHAR/NVARCHAR2 确保 Unicode 兼容（中文等多字节字符不会截断）
        oracleRules.add(new TypeMappingRule("CHAR\\((\\d+)\\)", "NCHAR($1)", 30, false,
                "CHAR -> NCHAR (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("VARCHAR\\((\\d+)\\)", "NVARCHAR2($1)", 31, false,
                "VARCHAR -> NVARCHAR2 (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("TINYTEXT", "NVARCHAR2(255)", 40, false,
                "TINYTEXT -> NVARCHAR2(255)"));
        oracleRules.add(new TypeMappingRule("TEXT", "NCLOB", 41, false,
                "TEXT -> NCLOB (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("MEDIUMTEXT", "NCLOB", 42, false,
                "MEDIUMTEXT -> NCLOB (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("LONGTEXT", "NCLOB", 43, false,
                "LONGTEXT -> NCLOB (Unicode 兼容)"));
        oracleRules.add(new TypeMappingRule("BINARY\\((\\d+)\\)", "RAW($1)", 50, false,
                "BINARY -> RAW"));
        oracleRules.add(new TypeMappingRule("VARBINARY\\((\\d+)\\)", "RAW($1)", 51, false,
                "VARBINARY -> RAW"));
        oracleRules.add(new TypeMappingRule("TINYBLOB", "BLOB", 52, false,
                "TINYBLOB -> BLOB"));
        oracleRules.add(new TypeMappingRule("BLOB", "BLOB", 53, false,
                "BLOB 兼容"));
        oracleRules.add(new TypeMappingRule("MEDIUMBLOB", "BLOB", 54, false,
                "MEDIUMBLOB -> BLOB"));
        oracleRules.add(new TypeMappingRule("LONGBLOB", "BLOB", 55, false,
                "LONGBLOB -> BLOB"));

        // ==================== 日期时间类型 ====================
        oracleRules.add(new TypeMappingRule("DATE", "DATE", 60, false,
                "DATE 兼容"));
        oracleRules.add(new TypeMappingRule("TIME(?:\\(\\d+\\))?", "VARCHAR2(20)", 61, true,
                "TIME -> VARCHAR2(20) (Oracle无TIME类型)"));
        oracleRules.add(new TypeMappingRule("DATETIME(?:\\(\\d+\\))?", "TIMESTAMP", 62, false,
                "DATETIME -> TIMESTAMP"));
        oracleRules.add(new TypeMappingRule("TIMESTAMP(?:\\(\\d+\\))?", "TIMESTAMP", 63, false,
                "TIMESTAMP 兼容"));
        oracleRules.add(new TypeMappingRule("YEAR(?:\\(\\d+\\))?", "NUMBER(4)", 64, false,
                "YEAR -> NUMBER(4)"));

        // ==================== 特殊类型 ====================
        oracleRules.add(new TypeMappingRule("ENUM\\(.*?\\)", "VARCHAR2(255)", 70, true,
                "ENUM -> VARCHAR2(255) (有损转换)"));
        oracleRules.add(new TypeMappingRule("SET\\(.*?\\)", "VARCHAR2(1024)", 71, true,
                "SET -> VARCHAR2(1024) (有损转换)"));
        oracleRules.add(new TypeMappingRule("JSON", "CLOB", 80, false,
                "JSON -> CLOB"));
        oracleRules.add(new TypeMappingRule("BIT\\(1\\)", "NUMBER(1)", 82, false,
                "BIT(1) -> NUMBER(1)"));
        oracleRules.add(new TypeMappingRule("BIT\\((\\d+)\\)", "NUMBER(10)", 83, false,
                "BIT(n) -> NUMBER(10)"));
        oracleRules.add(new TypeMappingRule("BOOL(?:EAN)?", "NUMBER(1)", 84, false,
                "BOOLEAN -> NUMBER(1)"));

        // ==================== 通配符兜底规则 ====================
        oracleRules.add(new TypeMappingRule(".*", "$0", 999, false,
                "未匹配类型保持原样"));

        oracleRules.sort(Comparator.comparingInt(TypeMappingRule::getPriority));
        mappingRules.put("ORACLE", oracleRules);
        log.info("Initialized {} TiDB->Oracle type mapping rules", oracleRules.size());
    }
}
