package cn.easyreport.combinesync.mapper;

import java.util.List;
import cn.easyreport.combinesync.domain.ErCombineSync;

/**
 * 合并同步任务Mapper接口
 *
 * @author easyreport
 * @date 2026-01-18
 */
public interface ErCombineSyncMapper
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
     * 删除合并同步任务
     *
     * @param combineId 合并同步任务主键
     * @return 结果
     */
    public int deleteErCombineSyncByCombineId(Long combineId);

    /**
     * 批量删除合并同步任务
     *
     * @param combineIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteErCombineSyncByCombineIds(Long[] combineIds);

    /**
     * 校验任务名称是否唯一
     *
     * @param combineName 任务名称
     * @return 结果
     */
    public ErCombineSync checkCombineNameUnique(String combineName);
}
