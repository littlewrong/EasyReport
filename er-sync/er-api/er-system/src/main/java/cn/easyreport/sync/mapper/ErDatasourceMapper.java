package cn.easyreport.sync.mapper;

import java.util.List;
import cn.easyreport.sync.domain.ErDatasource;

/**
 * 数据源管理Mapper接口
 *
 * @author easyreport
 * @date 2026-01-16
 */
public interface ErDatasourceMapper
{
    /**
     * 查询数据源管理
     *
     * @param datasourceId 数据源管理主键
     * @return 数据源管理
     */
    public ErDatasource selectErDatasourceByDatasourceId(Long datasourceId);

    /**
     * 查询数据源管理列表
     *
     * @param erDatasource 数据源管理
     * @return 数据源管理集合
     */
    public List<ErDatasource> selectErDatasourceList(ErDatasource erDatasource);

    /**
     * 新增数据源管理
     *
     * @param erDatasource 数据源管理
     * @return 结果
     */
    public int insertErDatasource(ErDatasource erDatasource);

    /**
     * 修改数据源管理
     *
     * @param erDatasource 数据源管理
     * @return 结果
     */
    public int updateErDatasource(ErDatasource erDatasource);

    /**
     * 删除数据源管理
     *
     * @param datasourceId 数据源管理主键
     * @return 结果
     */
    public int deleteErDatasourceByDatasourceId(Long datasourceId);

    /**
     * 批量删除数据源管理
     *
     * @param datasourceIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteErDatasourceByDatasourceIds(Long[] datasourceIds);

    /**
     * 校验数据源名称是否唯一
     *
     * @param datasourceName 数据源名称
     * @return 结果
     */
    public ErDatasource checkDatasourceNameUnique(String datasourceName);
}
