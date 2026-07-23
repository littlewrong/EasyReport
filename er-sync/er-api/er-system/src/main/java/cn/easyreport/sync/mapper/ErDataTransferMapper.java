package cn.easyreport.sync.mapper;

import java.util.List;
import cn.easyreport.sync.domain.ErDataTransfer;

/**
 * 数据同步任务Mapper接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface ErDataTransferMapper
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
     * 删除数据同步任务
     *
     * @param transferId 数据同步任务主键
     * @return 结果
     */
    public int deleteErDataTransferByTransferId(Long transferId);

    /**
     * 批量删除数据同步任务
     *
     * @param transferIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteErDataTransferByTransferIds(Long[] transferIds);

    /**
     * 校验任务名称是否唯一
     *
     * @param transferName 任务名称
     * @return 结果
     */
    public ErDataTransfer checkTransferNameUnique(String transferName);
}
