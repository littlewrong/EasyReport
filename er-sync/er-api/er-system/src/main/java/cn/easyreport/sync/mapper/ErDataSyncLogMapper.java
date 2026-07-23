package cn.easyreport.sync.mapper;

import java.util.List;
import cn.easyreport.sync.domain.ErDataSyncLog;

/**
 * 数据同步日志Mapper接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface ErDataSyncLogMapper
{
    /**
     * 查询数据同步日志
     *
     * @param logId 日志主键
     * @return 数据同步日志
     */
    public ErDataSyncLog selectErDataSyncLogByLogId(Long logId);

    /**
     * 查询数据同步日志列表
     *
     * @param erDataSyncLog 数据同步日志
     * @return 数据同步日志集合
     */
    public List<ErDataSyncLog> selectErDataSyncLogList(ErDataSyncLog erDataSyncLog);

    /**
     * 根据同步任务ID查询日志列表
     *
     * @param syncId 同步任务ID
     * @return 数据同步日志集合
     */
    public List<ErDataSyncLog> selectErDataSyncLogBySyncId(Long syncId);

    /**
     * 新增数据同步日志
     *
     * @param erDataSyncLog 数据同步日志
     * @return 结果
     */
    public int insertErDataSyncLog(ErDataSyncLog erDataSyncLog);

    /**
     * 批量新增数据同步日志
     *
     * @param logList 数据同步日志列表
     * @return 结果
     */
    public int batchInsertErDataSyncLog(List<ErDataSyncLog> logList);

    /**
     * 删除数据同步日志
     *
     * @param logId 日志主键
     * @return 结果
     */
    public int deleteErDataSyncLogByLogId(Long logId);

    /**
     * 根据同步任务ID删除日志
     *
     * @param syncId 同步任务ID
     * @return 结果
     */
    public int deleteErDataSyncLogBySyncId(Long syncId);

    /**
     * 清理指定天数前的日志
     *
     * @param days 天数
     * @return 结果
     */
    public int cleanErDataSyncLogByDays(int days);
}
