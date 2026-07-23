package cn.easyreport.sync.strategy;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据库策略工厂
 * 根据数据库类型返回对应的策略实现
 */
public class DatabaseStrategyFactory {

    private static final Map<String, DatabaseStrategy> STRATEGIES = new HashMap<>();

    static {
        register(new MySqlStrategy());
        register(new TiDbStrategy());
        register(new StarRocksStrategy());
        register(new SqlServerStrategy());
        register(new PostgreSqlStrategy());
        register(new OracleStrategy());
    }

    private static void register(DatabaseStrategy strategy) {
        STRATEGIES.put(strategy.getDatabaseType().toUpperCase(), strategy);
    }

    /**
     * 根据数据库类型获取策略
     */
    public static DatabaseStrategy getStrategy(String dbType) {
        if (dbType == null) {
            return null;
        }
        return STRATEGIES.get(dbType.toUpperCase());
    }

    /**
     * 是否支持该数据库类型
     */
    public static boolean isSupported(String dbType) {
        return dbType != null && STRATEGIES.containsKey(dbType.toUpperCase());
    }
}
