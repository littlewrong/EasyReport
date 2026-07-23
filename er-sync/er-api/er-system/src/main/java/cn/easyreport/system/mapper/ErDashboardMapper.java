package cn.easyreport.system.mapper;

import java.util.List;
import java.util.Map;

/**
 * 首页看板统计Mapper
 *
 * @author easyreport
 */
public interface ErDashboardMapper
{
    /** 数据源总数 */
    public int countDatasource();

    /** 连接正常的数据源数 */
    public int countDatasourceConnected();

    /** 连接异常的数据源数 */
    public int countDatasourceFailed();

    /** 数据同步任务总数（含结构同步+数据同步） */
    public int countTransferTask();

    /** 正在运行的同步任务数 */
    public int countTransferRunning();

    /** 今日同步执行次数 */
    public int countTodaySyncTotal();

    /** 今日同步成功次数 */
    public int countTodaySyncSuccess();

    /** 今日同步失败次数 */
    public int countTodaySyncFail();

    /** 今日同步记录总行数 */
    public Long countTodaySyncRows();

    /** 近7天同步趋势（日期, 成功数, 失败数） */
    public List<Map<String, Object>> selectSyncTrend7Days();

    /** 数据源类型分布 */
    public List<Map<String, Object>> selectDatasourceTypeStats();

    /** 同步任务状态分布（数据同步） */
    public List<Map<String, Object>> selectTransferStatusStats();

    /** 结构同步任务状态分布 */
    public List<Map<String, Object>> selectSyncStatusStats();

    /** 最近10条同步日志 */
    public List<Map<String, Object>> selectRecentTransferLogs();
}
