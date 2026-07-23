package cn.easyreport.combinesync.service;

import java.util.List;
import java.util.Map;
import cn.easyreport.combinesync.domain.ErCombineSync;
import cn.easyreport.combinesync.domain.ErCombineSyncLog;
import cn.easyreport.combinesync.domain.ErCombineSyncProgress;

/**
 * 合并同步任务Service接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface IErCombineSyncService
{
    /**
     * 查询合并同步任务
     *
     * @param combineId 合并同步任务主键
     * @return 合并同步任务
     */
    public ErCombineSync selectErCombineSyncByCombineId(Long combineId);

    /**
     * 查询合并同步任务列表
     *
     * @param erCombineSync 合并同步任务
     * @return 合并同步任务集合
     */
    public List<ErCombineSync> selectErCombineSyncList(ErCombineSync erCombineSync);

    /**
     * 新增合并同步任务
     *
     * @param erCombineSync 合并同步任务
     * @return 结果
     */
    public int insertErCombineSync(ErCombineSync erCombineSync);

    /**
     * 修改合并同步任务
     *
     * @param erCombineSync 合并同步任务
     * @return 结果
     */
    public int updateErCombineSync(ErCombineSync erCombineSync);

    /**
     * 批量删除合并同步任务
     *
     * @param combineIds 需要删除的合并同步任务主键集合
     * @return 结果
     */
    public int deleteErCombineSyncByCombineIds(Long[] combineIds);

    /**
     * 删除合并同步任务信息
     *
     * @param combineId 合并同步任务主键
     * @return 结果
     */
    public int deleteErCombineSyncByCombineId(Long combineId);

    /**
     * 校验任务名称是否唯一
     *
     * @param erCombineSync 合并同步任务信息
     * @return 结果
     */
    public boolean checkCombineNameUnique(ErCombineSync erCombineSync);

    /**
     * 执行合并同步任务（异步执行）
     *
     * @param combineId 合并任务ID
     * @return 同步结果
     */
    public Map<String, Object> executeCombineSync(Long combineId);

    /**
     * 预览合并同步（查询匹配的分库分表列表）
     *
     * @param combineId 合并任务ID
     * @return 包含表列表和统计信息的Map
     */
    public Map<String, Object> previewCombineSync(Long combineId);

    /**
     * 查询同步日志列表
     *
     * @param erCombineSyncLog 同步日志
     * @return 同步日志集合
     */
    public List<ErCombineSyncLog> selectErCombineSyncLogList(ErCombineSyncLog erCombineSyncLog);

    /**
     * 根据合并任务ID查询日志
     *
     * @param combineId 合并任务ID
     * @return 同步日志集合
     */
    public List<ErCombineSyncLog> selectErCombineSyncLogByCombineId(Long combineId);

    /**
     * 查询每张源表的最后同步时间戳
     *
     * @param combineId 合并任务ID
     * @return 表级同步进度列表
     */
    public List<ErCombineSyncProgress> selectProgressByCombineId(Long combineId);

    /**
     * 从目标表最大时间戳刷新每表增量进度
     *
     * @param combineId 合并任务ID
     * @return 刷新结果
     */
    public Map<String, Object> refreshProgressFromTarget(Long combineId);

    /**
     * 获取合并同步任务的实时进度信息
     *
     * @param combineId 合并任务ID
     * @return 包含同步进度、状态和结果的任务信息
     */
    public ErCombineSync getSyncProgress(Long combineId);

    /**
     * 停止合并同步任务（优雅停止，当前表处理完成后停止）
     *
     * @param combineId 合并任务ID
     * @return 停止结果
     */
    public Map<String, Object> stopCombineSync(Long combineId);
}
