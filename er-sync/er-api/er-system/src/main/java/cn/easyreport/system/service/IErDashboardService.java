package cn.easyreport.system.service;

import java.util.Map;

/**
 * 首页看板Service接口
 *
 * @author easyreport
 */
public interface IErDashboardService
{
    /** 获取汇总统计数据 */
    public Map<String, Object> getSummary();

    /** 获取近7天同步趋势 */
    public Map<String, Object> getSyncTrend();

    /** 获取数据源类型分布 */
    public Map<String, Object> getDatasourceTypeStats();

    /** 获取任务状态分布 */
    public Map<String, Object> getTaskStatusStats();

    /** 获取最近同步日志 */
    public Map<String, Object> getRecentLogs();
}
