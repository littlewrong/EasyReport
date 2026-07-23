package cn.easyreport.sync.mapper;

import java.util.List;
import cn.easyreport.sync.domain.ErDataTransferLog;

/**
 * 数据同步日志Mapper接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface ErDataTransferLogMapper
{
    /**
     * 查询数据同步日志
     *
     * @param logId 数据同步日志主键
     * @return 数据同步日志
     */
    public ErDataTransferLog selectErDataTransferLogByLogId(Long logId);

    /**
     * 查询数据同步日志列表
     *
     * @param erDataTransferLog 数据同步日志
     * @return 数据同步日志集合
     */
    public List<ErDataTransferLog> selectErDataTransferLogList(ErDataTransferLog erDataTransferLog);

    /**
     * 根据同步任务ID查询日志
     *
     * @param transferId 同步任务ID
     * @return 数据同步日志集合
     */
    public List<ErDataTransferLog> selectErDataTransferLogByTransferId(Long transferId);

    /**
     * 新增数据同步日志
     *
     * @param erDataTransferLog 数据同步日志
     * @return 结果
     */
    public int insertErDataTransferLog(ErDataTransferLog erDataTransferLog);

    /**
     * 修改数据同步日志
     *
     * @param erDataTransferLog 数据同步日志
     * @return 结果
     */
    public int updateErDataTransferLog(ErDataTransferLog erDataTransferLog);

    /**
     * 删除数据同步日志
     *
     * @param logId 数据同步日志主键
     * @return 结果
     */
    public int deleteErDataTransferLogByLogId(Long logId);

    /**
     * 批量删除数据同步日志
     *
     * @param logIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteErDataTransferLogByLogIds(Long[] logIds);

    /**
     * 根据同步任务ID删除日志
     *
     * @param transferId 同步任务ID
     * @return 结果
     */
    public int deleteErDataTransferLogByTransferId(Long transferId);
}
