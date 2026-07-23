package cn.easyreport.sync.mapper;

import java.util.List;

/**
 * 数据库类型映射器接口
 * 每个源数据库实现一个TypeMapper，定义到各个目标数据库的映射规则
 *
 * 实现类应该：
 * 1. 定义从当前数据库到其他数据库的类型映射规则
 * 2. 支持类型模式匹配（正则表达式）
 * 3. 支持参数提取和替换（如 VARCHAR($1)）
 *
 * 示例：
 * - MySqlTypeMapper: 定义 MySQL → TiDB, MySQL → StarRocks 等映射
 * - TiDbTypeMapper: 定义 TiDB → MySQL, TiDB → StarRocks 等映射
 */
public interface DatabaseTypeMapper {

    /**
     * 获取源数据库类型
     * @return 数据库类型标识，如 "MYSQL", "TIDB", "STARROCKS"
     */
    String getSourceDatabaseType();

    /**
     * 映射类型
     *
     * @param targetDbType 目标数据库类型（MYSQL, TIDB, STARROCKS等）
     * @param sourceType 源类型（如 VARCHAR(50), INT(11), DECIMAL(10,2)）
     * @return 映射后的目标类型
     */
    String mapType(String targetDbType, String sourceType);

    /**
     * 检查是否支持目标数据库
     *
     * @param targetDbType 目标数据库类型
     * @return true表示支持映射到该数据库
     */
    boolean supportsTarget(String targetDbType);

    /**
     * 检查类型转换是否有损
     * 有损转换示例：
     * - ENUM → VARCHAR (语义丢失)
     * - TIMESTAMP → DATETIME (时区信息丢失)
     * - BLOB → VARBINARY(65535) (可能截断)
     *
     * @param targetDbType 目标数据库类型
     * @param sourceType 源类型
     * @return true表示有损转换，false表示无损转换
     */
    boolean isLossyConversion(String targetDbType, String sourceType);

    /**
     * 获取支持的目标数据库列表
     *
     * @return 支持的目标数据库类型列表
     */
    List<String> getSupportedTargets();
}
