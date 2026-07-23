package cn.easyreport.sync.dialect;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.registry.DatabaseComponentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扩展的方言工厂
 *
 * 结合了动态注册和原有的硬编码实现：
 * 1. 首先尝试从 DatabaseComponentRegistry 获取（支持扩展的数据库）
 * 2. 如果注册中心没有找到，回退到原有的 DialectFactory（MySQL/TiDB）
 *
 * 这样设计的好处：
 * - 保持向后兼容：原有的 MySQL/TiDB 代码继续工作
 * - 支持扩展：新数据库只需配置文件 + 新增类，无需修改此工厂
 * - 平滑过渡：未来可以逐步将 MySQL/TiDB 也迁移到配置化
 */
public class ExtendedDialectFactory {

    private static final Logger log = LoggerFactory.getLogger(ExtendedDialectFactory.class);

    /**
     * 构建 SchemaDialect 实例
     *
     * @param ds 数据源
     * @return SchemaDialect 实例
     * @throws UnsupportedOperationException 如果数据源类型不支持
     */
    public static SchemaDialect build(ErDatasource ds) {
        if (ds == null) {
            throw new IllegalArgumentException("datasource is null");
        }

        String type = ds.getDatasourceType();
        if (type == null) {
            throw new IllegalArgumentException("datasource type is null");
        }

        // 1. 尝试从注册中心获取
        SchemaDialect dialect = DatabaseComponentRegistry.getDialect(ds);
        if (dialect != null) {
            log.debug("Using registered dialect for database type: {}", type);
            return dialect;
        }

        // 2. 回退到原有工厂实现
        log.debug("Registered dialect not found for {}, falling back to original DialectFactory", type);
        return DialectFactory.build(ds);
    }

    /**
     * 私有构造函数，防止实例化
     */
    private ExtendedDialectFactory() {
        throw new UnsupportedOperationException("Factory class cannot be instantiated");
    }
}
