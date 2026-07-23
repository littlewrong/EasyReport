package cn.easyreport.sync.service;

import java.util.List;
import cn.easyreport.sync.domain.ErDatasource;

/**
 * 数据源管理Service接口
 *
 * @author easyreport
 * @date 2026-01-16
 */
public interface IErDatasourceService
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
     * 批量删除数据源管理
     *
     * @param datasourceIds 需要删除的数据源管理主键集合
     * @return 结果
     */
    public int deleteErDatasourceByDatasourceIds(Long[] datasourceIds);

    /**
     * 删除数据源管理信息
     *
     * @param datasourceId 数据源管理主键
     * @return 结果
     */
    public int deleteErDatasourceByDatasourceId(Long datasourceId);

    /**
     * 校验数据源名称是否唯一
     *
     * @param erDatasource 数据源信息
     * @return 结果
     */
    public boolean checkDatasourceNameUnique(ErDatasource erDatasource);

    /**
     * 测试数据源连接
     *
     * @param erDatasource 数据源信息
     * @return 结果
     */
    public String testConnection(ErDatasource erDatasource);
}
