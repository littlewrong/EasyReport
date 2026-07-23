package cn.easyreport.sync.registry;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.dialect.SchemaDialect;
import cn.easyreport.sync.extractor.SchemaExtractor;
import cn.easyreport.sync.mapper.DatabaseTypeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库组件注册中心
 *
 * 通过配置文件动态注册和管理各数据库的组件：
 * - TypeMapper: 类型映射器
 * - Extractor: Schema提取器
 * - Dialect: SQL方言
 *
 * 配置文件格式：type-mapper-registry.properties
 * db.<DATABASE_TYPE>=<TypeMapperClass>,<ExtractorClass>,<DialectClass>
 *
 * 使用示例：
 * <pre>
 * // 初始化（应用启动时调用一次）
 * DatabaseComponentRegistry.initialize();
 *
 * // 获取类型映射器
 * DatabaseTypeMapper mapper = DatabaseComponentRegistry.getTypeMapper("MYSQL");
 *
 * // 获取提取器
 * SchemaExtractor extractor = DatabaseComponentRegistry.getExtractor(datasource);
 *
 * // 获取方言
 * SchemaDialect dialect = DatabaseComponentRegistry.getDialect(datasource);
 * </pre>
 */
public class DatabaseComponentRegistry {

    private static final Logger log = LoggerFactory.getLogger(DatabaseComponentRegistry.class);

    private static final String REGISTRY_FILE = "type-mapper-registry.properties";
    private static final String DB_PREFIX = "db.";

    /** 是否已初始化 */
    private static volatile boolean initialized = false;

    /** 类型映射器缓存: dbType -> DatabaseTypeMapper */
    private static final Map<String, DatabaseTypeMapper> typeMapperCache = new ConcurrentHashMap<>();

    /** 组件类信息缓存: dbType -> ComponentClasses */
    private static final Map<String, ComponentClasses> componentClassCache = new ConcurrentHashMap<>();

    /**
     * 组件类信息
     */
    private static class ComponentClasses {
        String typeMapperClass;
        String extractorClass;
        String dialectClass;

        ComponentClasses(String typeMapperClass, String extractorClass, String dialectClass) {
            this.typeMapperClass = typeMapperClass;
            this.extractorClass = extractorClass;
            this.dialectClass = dialectClass;
        }
    }

    /**
     * 初始化注册中心
     * 从配置文件加载所有数据库组件信息
     *
     * @throws RuntimeException 如果配置文件加载失败
     */
    public static synchronized void initialize() {
        if (initialized) {
            log.debug("DatabaseComponentRegistry already initialized");
            return;
        }

        log.info("Initializing DatabaseComponentRegistry...");

        try (InputStream is = DatabaseComponentRegistry.class.getClassLoader().getResourceAsStream(REGISTRY_FILE)) {
            if (is == null) {
                log.warn("Registry file not found: {}, using empty registry", REGISTRY_FILE);
                initialized = true;
                return;
            }

            Properties props = new Properties();
            props.load(is);

            int count = 0;
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith(DB_PREFIX)) {
                    continue;
                }

                String dbType = key.substring(DB_PREFIX.length()).toUpperCase();
                String value = props.getProperty(key);

                if (value == null || value.trim().isEmpty()) {
                    log.warn("Empty configuration for database type: {}", dbType);
                    continue;
                }

                // 解析配置：TypeMapperClass,ExtractorClass,DialectClass
                String[] parts = value.split(",");
                if (parts.length != 3) {
                    log.warn("Invalid configuration format for {}: {}", dbType, value);
                    continue;
                }

                String typeMapperClass = parts[0].trim();
                String extractorClass = parts[1].trim();
                String dialectClass = parts[2].trim();

                componentClassCache.put(dbType, new ComponentClasses(typeMapperClass, extractorClass, dialectClass));
                count++;

                log.info("Registered database type: {} -> TypeMapper={}, Extractor={}, Dialect={}",
                        dbType, typeMapperClass, extractorClass, dialectClass);
            }

            log.info("DatabaseComponentRegistry initialized successfully, {} database types registered", count);
            initialized = true;

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize DatabaseComponentRegistry", e);
        }
    }

    /**
     * 获取类型映射器
     *
     * @param dbType 数据库类型（如 MYSQL, TIDB）
     * @return 类型映射器实例，如果未注册则返回null
     */
    public static DatabaseTypeMapper getTypeMapper(String dbType) {
        ensureInitialized();

        if (dbType == null) {
            return null;
        }

        String key = dbType.toUpperCase();

        // 从缓存获取
        DatabaseTypeMapper cached = typeMapperCache.get(key);
        if (cached != null) {
            return cached;
        }

        // 加载并缓存
        ComponentClasses classes = componentClassCache.get(key);
        if (classes == null || classes.typeMapperClass == null) {
            log.debug("No TypeMapper registered for database type: {}", dbType);
            return null;
        }

        try {
            Class<?> clazz = Class.forName(classes.typeMapperClass);
            DatabaseTypeMapper mapper = (DatabaseTypeMapper) clazz.getDeclaredConstructor().newInstance();
            typeMapperCache.put(key, mapper);
            log.debug("Instantiated TypeMapper for {}: {}", dbType, classes.typeMapperClass);
            return mapper;
        } catch (Exception e) {
            log.error("Failed to instantiate TypeMapper for {}: {}", dbType, classes.typeMapperClass, e);
            return null;
        }
    }

    /**
     * 获取Schema提取器
     *
     * @param datasource 数据源
     * @return 提取器实例，如果未注册则返回null
     */
    public static SchemaExtractor getExtractor(ErDatasource datasource) {
        ensureInitialized();

        if (datasource == null || datasource.getDatasourceType() == null) {
            return null;
        }

        String dbType = datasource.getDatasourceType().toUpperCase();
        ComponentClasses classes = componentClassCache.get(dbType);

        if (classes == null || classes.extractorClass == null) {
            log.debug("No Extractor registered for database type: {}", dbType);
            return null;
        }

        try {
            Class<?> clazz = Class.forName(classes.extractorClass);
            Constructor<?> constructor = clazz.getConstructor(ErDatasource.class);
            SchemaExtractor extractor = (SchemaExtractor) constructor.newInstance(datasource);
            log.debug("Instantiated Extractor for {}: {}", dbType, classes.extractorClass);
            return extractor;
        } catch (Exception e) {
            log.error("Failed to instantiate Extractor for {}: {}", dbType, classes.extractorClass, e);
            return null;
        }
    }

    /**
     * 获取SQL方言
     *
     * @param datasource 数据源
     * @return 方言实例，如果未注册则返回null
     */
    public static SchemaDialect getDialect(ErDatasource datasource) {
        ensureInitialized();

        if (datasource == null || datasource.getDatasourceType() == null) {
            return null;
        }

        String dbType = datasource.getDatasourceType().toUpperCase();
        ComponentClasses classes = componentClassCache.get(dbType);

        if (classes == null || classes.dialectClass == null) {
            log.debug("No Dialect registered for database type: {}", dbType);
            return null;
        }

        try {
            Class<?> clazz = Class.forName(classes.dialectClass);
            SchemaDialect dialect = (SchemaDialect) clazz.getDeclaredConstructor().newInstance();
            log.debug("Instantiated Dialect for {}: {}", dbType, classes.dialectClass);
            return dialect;
        } catch (Exception e) {
            log.error("Failed to instantiate Dialect for {}: {}", dbType, classes.dialectClass, e);
            return null;
        }
    }

    /**
     * 获取所有已注册的数据库类型
     *
     * @return 数据库类型列表
     */
    public static List<String> getRegisteredDatabaseTypes() {
        ensureInitialized();
        return new ArrayList<>(componentClassCache.keySet());
    }

    /**
     * 检查指定数据库类型是否已注册
     *
     * @param dbType 数据库类型
     * @return true表示已注册
     */
    public static boolean isRegistered(String dbType) {
        ensureInitialized();
        return dbType != null && componentClassCache.containsKey(dbType.toUpperCase());
    }

    /**
     * 清空缓存（主要用于测试）
     */
    public static synchronized void clearCache() {
        typeMapperCache.clear();
        componentClassCache.clear();
        initialized = false;
        log.info("DatabaseComponentRegistry cache cleared");
    }

    /**
     * 确保注册中心已初始化
     */
    private static void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    /**
     * 私有构造函数，防止实例化
     */
    private DatabaseComponentRegistry() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
