package cn.easyreport.sync.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
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

    /** 在配置库原子认领，禁止不同实例同时启动运行中/停止中的同一个任务。 */
    int claimTransfer(ErDataTransfer erDataTransfer);

    /** 远程仅运行中的任务接收请求；仍有本地运行锁时也覆盖准备阶段及旧版误标。 */
    int requestTransferStop(@Param("transferId") Long transferId,
                            @Param("syncProgress") String syncProgress,
                            @Param("allowLocalActive") boolean allowLocalActive);

    /** 原子保留并完成已提交的停止请求，防止末次成功/失败覆盖停止状态。 */
    int finishTransfer(ErDataTransfer erDataTransfer);

    /** 停止中及结束状态不再接受工作线程的展示进度。 */
    int updateTransferProgress(ErDataTransfer erDataTransfer);

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
