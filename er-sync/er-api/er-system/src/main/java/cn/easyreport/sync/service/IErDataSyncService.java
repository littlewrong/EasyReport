package cn.easyreport.sync.service;

import java.util.List;
import java.util.Map;
import cn.easyreport.sync.domain.ErDataSync;
import cn.easyreport.sync.domain.ErDataSyncLog;

/**
 * 数据同步任务Service接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface IErDataSyncService
{
    /**
     * 查询数据同步任务
     *
     * @param syncId 数据同步任务主键
     * @return 数据同步任务
     */
    public ErDataSync selectErDataSyncBySyncId(Long syncId);

    /**
     * 查询数据同步任务列表
     *
     * @param erDataSync 数据同步任务
     * @return 数据同步任务集合
     */
    public List<ErDataSync> selectErDataSyncList(ErDataSync erDataSync);

    /**
     * 新增数据同步任务
     *
     * @param erDataSync 数据同步任务
     * @return 结果
     */
    public int insertErDataSync(ErDataSync erDataSync);

    /**
     * 修改数据同步任务
     *
     * @param erDataSync 数据同步任务
     * @return 结果
     */
    public int updateErDataSync(ErDataSync erDataSync);

    /**
     * 批量删除数据同步任务
     *
     * @param syncIds 需要删除的数据同步任务主键集合
     * @return 结果
     */
    public int deleteErDataSyncBySyncIds(Long[] syncIds);

    /**
     * 删除数据同步任务信息
     *
     * @param syncId 数据同步任务主键
     * @return 结果
     */
    public int deleteErDataSyncBySyncId(Long syncId);

    /**
     * 校验任务名称是否唯一
     *
     * @param erDataSync 数据同步任务信息
     * @return 结果
     */
    public boolean checkSyncNameUnique(ErDataSync erDataSync);

    /**
     * 执行同步任务（同步执行）
     *
     * @param syncId 同步任务ID
     * @return 同步结果
     */
    public Map<String, Object> executeSync(Long syncId);

    /**
     * 异步执行同步任务
     *
     * @param syncId 同步任务ID
     * @return 提交结果
     */
    public Map<String, Object> executeSyncAsync(Long syncId);

    /**
     * 预览同步（查询匹配的表列表）
     *
     * @param syncId 同步任务ID
     * @return 包含表列表和统计信息的Map，key: tables(表列表), schemaCount(库数量), tableCount(表数量)
     */
    public Map<String, Object> previewSync(Long syncId);

    /**
     * 查询同步日志列表
     *
     * @param erDataSyncLog 同步日志
     * @return 同步日志集合
     */
    public List<ErDataSyncLog> selectErDataSyncLogList(ErDataSyncLog erDataSyncLog);

    /**
     * 根据同步任务ID查询日志
     *
     * @param syncId 同步任务ID
     * @return 同步日志集合
     */
    public List<ErDataSyncLog> selectErDataSyncLogBySyncId(Long syncId);

    /**
     * 停止架构同步任务
     *
     * @param syncId 同步任务ID
     * @return 停止结果
     */
    public Map<String, Object> stopSync(Long syncId);

    /**
     * 获取同步任务的实时进度信息
     *
     * @param syncId 同步任务ID
     * @return 包含同步进度、状态和结果的任务信息
     */
    public ErDataSync getSyncProgress(Long syncId);
}
