package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;

import java.sql.Connection;
import java.sql.Statement;

/**
 * 数据同步连接级初始化。
 *
 * Executor 负责同步流程；连接会话参数属于数据源方言，集中放在这里，避免流程基类膨胀。
 */
final class DataSyncConnectionInitializer {

    private DataSyncConnectionInitializer() {
    }

    static void initialize(ErDatasource ds, Connection conn) throws Exception {
        if (ds == null || conn == null || ds.getDatasourceType() == null) {
            return;
        }
        String dbType = ds.getDatasourceType().toUpperCase();
        if ("MYSQL".equals(dbType) || "TIDB".equals(dbType) || "STARROCKS".equals(dbType)) {
            initializeMySqlProtocolSession(conn);
        }
    }

    private static void initializeMySqlProtocolSession(Connection conn) {
        try (Statement st = conn.createStatement()) {
            // 28800s = 8 小时，足以覆盖单表大批量同步耗时。
            st.execute("SET SESSION wait_timeout = 28800");
        } catch (Exception e) {
            System.out.println("[DataSyncConnectionInitializer] 设置 wait_timeout 失败（忽略，继续执行）: " + e.getMessage());
        }
        try (Statement st = conn.createStatement()) {
            st.execute("SET SESSION interactive_timeout = 28800");
        } catch (Exception ignored) {
            // StarRocks 老版本可能不支持 interactive_timeout，忽略即可。
        }
    }
}
