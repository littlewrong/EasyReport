package cn.easyreport.sync.strategy;

/**
 * TiDB 数据库策略
 * TiDB 与 MySQL 高度兼容，直接复用 MySQL 策略
 */
public class TiDbStrategy extends MySqlStrategy {

    @Override
    public String getDatabaseType() {
        return "TIDB";
    }
}
