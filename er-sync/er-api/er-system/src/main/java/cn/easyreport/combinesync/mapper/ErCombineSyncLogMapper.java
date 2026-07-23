package cn.easyreport.combinesync.mapper;

import java.util.List;
import cn.easyreport.combinesync.domain.ErCombineSyncLog;

/**
 * 合并同步日志Mapper接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface ErCombineSyncLogMapper
{
    /**
     * 查询合并同步日志
     *
     * @param logId 日志主键
     * @return 合并同步日志
     */
    public ErCombineSyncLog selectErCombineSyncLogByLogId(Long logId);

    /**
     * 查询合并同步日志列表
     *
     * @param erCombineSyncLog 合并同步日志
     * @return 合并同步日志集合
     */
    public List<ErCombineSyncLog> selectErCombineSyncLogList(ErCombineSyncLog erCombineSyncLog);

    /**
     * 根据合并任务ID查询日志
     *
     * @param combineId 合并任务ID
     * @return 合并同步日志集合
     */
    public List<ErCombineSyncLog> selectErCombineSyncLogByCombineId(Long combineId);

    /**
     * 新增合并同步日志
     *
     * @param erCombineSyncLog 合并同步日志
     * @return 结果
     */
    public int insertErCombineSyncLog(ErCombineSyncLog erCombineSyncLog);

    /**
     * 删除合并同步日志
     *
     * @param logId 日志主键
     * @return 结果
     */
    public int deleteErCombineSyncLogByLogId(Long logId);

    /**
     * 批量删除合并同步日志
     *
     * @param logIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteErCombineSyncLogByLogIds(Long[] logIds);

    /**
     * 根据合并任务ID删除日志
     *
     * @param combineId 合并任务ID
     * @return 结果
     */
    public int deleteErCombineSyncLogByCombineId(Long combineId);
}
