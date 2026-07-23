package cn.easyreport.sync.mapper;

import java.util.List;
import cn.easyreport.sync.domain.ErDataSync;

/**
 * 数据同步任务Mapper接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface ErDataSyncMapper
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
     * 删除数据同步任务
     *
     * @param syncId 数据同步任务主键
     * @return 结果
     */
    public int deleteErDataSyncBySyncId(Long syncId);

    /**
     * 批量删除数据同步任务
     *
     * @param syncIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteErDataSyncBySyncIds(Long[] syncIds);

    /**
     * 校验任务名称是否唯一
     *
     * @param syncName 任务名称
     * @return 结果
     */
    public ErDataSync checkSyncNameUnique(String syncName);
}
