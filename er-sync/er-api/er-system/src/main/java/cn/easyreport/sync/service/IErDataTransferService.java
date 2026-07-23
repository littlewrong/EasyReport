package cn.easyreport.sync.service;

import java.util.List;
import java.util.Map;
import cn.easyreport.sync.domain.ErDataTransfer;
import cn.easyreport.sync.domain.ErDataTransferLog;
import cn.easyreport.sync.domain.ErDataTransferProgress;

/**
 * 数据同步任务Service接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface IErDataTransferService
{
    /**
     * 查询数据同步任务
     *
     * @param transferId 数据同步任务主键
     * @return 数据同步任务
     */
    public ErDataTransfer selectErDataTransferByTransferId(Long transferId);

    /**
     * 查询数据同步任务列表
     *
     * @param erDataTransfer 数据同步任务
     * @return 数据同步任务集合
     */
    public List<ErDataTransfer> selectErDataTransferList(ErDataTransfer erDataTransfer);

    /**
     * 新增数据同步任务
     *
     * @param erDataTransfer 数据同步任务
     * @return 结果
     */
    public int insertErDataTransfer(ErDataTransfer erDataTransfer);

    /**
     * 修改数据同步任务
     *
     * @param erDataTransfer 数据同步任务
     * @return 结果
     */
    public int updateErDataTransfer(ErDataTransfer erDataTransfer);

    /**
     * 批量删除数据同步任务
     *
     * @param transferIds 需要删除的数据同步任务主键集合
     * @return 结果
     */
    public int deleteErDataTransferByTransferIds(Long[] transferIds);

    /**
     * 删除数据同步任务信息
     *
     * @param transferId 数据同步任务主键
     * @return 结果
     */
    public int deleteErDataTransferByTransferId(Long transferId);

    /**
     * 校验任务名称是否唯一
     *
     * @param erDataTransfer 数据同步任务信息
     * @return 结果
     */
    public boolean checkTransferNameUnique(ErDataTransfer erDataTransfer);

    /**
     * 执行同步任务（预留接口）
     *
     * @param transferId 同步任务ID
     * @return 同步结果
     */
    public Map<String, Object> executeTransfer(Long transferId);

    /**
     * 预览同步（查询匹配的表列表）
     *
     * @param transferId 同步任务ID
     * @return 包含表列表和统计信息的Map，key: tables(表列表), schemaCount(库数量), tableCount(表数量)
     */
    public Map<String, Object> previewTransfer(Long transferId);

    /**
     * 查询同步日志列表
     *
     * @param erDataTransferLog 同步日志
     * @return 同步日志集合
     */
    public List<ErDataTransferLog> selectErDataTransferLogList(ErDataTransferLog erDataTransferLog);

    /**
     * 根据同步任务ID查询日志
     *
     * @param transferId 同步任务ID
     * @return 同步日志集合
     */
    public List<ErDataTransferLog> selectErDataTransferLogByTransferId(Long transferId);

    /**
     * 查询每张源表的最后同步时间戳
     *
     * @param transferId 同步任务ID
     * @return 表级同步进度列表
     */
    public List<ErDataTransferProgress> selectProgressByTransferId(Long transferId);

    /**
     * 从目标表最大时间戳刷新每表增量进度
     *
     * @param transferId 同步任务ID
     * @return 刷新结果
     */
    public Map<String, Object> refreshProgressFromTarget(Long transferId);

    /**
     * 获取同步任务的实时进度信息
     *
     * @param transferId 同步任务ID
     * @return 包含同步进度、状态和结果的任务信息
     */
    public ErDataTransfer getSyncProgress(Long transferId);

    /**
     * 停止同步任务（优雅停止，当前表处理完成后停止）
     *
     * @param transferId 同步任务ID
     * @return 停止结果
     */
    public Map<String, Object> stopTransfer(Long transferId);
}
