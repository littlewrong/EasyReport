package cn.easyreport.sync.strategy;

/**
 * StarRocks 数据库策略
 */
public class StarRocksStrategy extends MySqlStrategy {

    @Override
    public String getDatabaseType() {
        return "STARROCKS";
    }

    @Override
    public String buildUseDatabase(String databaseName) {
        // StarRocks 需要显式切换数据库
        return String.format("USE `%s`", databaseName);
    }
}
