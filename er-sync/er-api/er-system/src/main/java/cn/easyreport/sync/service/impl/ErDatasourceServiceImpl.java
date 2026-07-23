package cn.easyreport.sync.service.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import cn.easyreport.common.utils.DateUtils;
import cn.easyreport.common.utils.StringUtils;
import cn.easyreport.sync.mapper.ErDatasourceMapper;
import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.service.IErDatasourceService;

/**
 * 数据源管理Service业务层处理
 *
 * @author easyreport
 * @date 2026-01-16
 */
@Service
public class ErDatasourceServiceImpl implements IErDatasourceService
{
    @Autowired
    private ErDatasourceMapper erDatasourceMapper;

    /**
     * 查询数据源管理
     *
     * @param datasourceId 数据源管理主键
     * @return 数据源管理
     */
    @Override
    public ErDatasource selectErDatasourceByDatasourceId(Long datasourceId)
    {
        return erDatasourceMapper.selectErDatasourceByDatasourceId(datasourceId);
    }

    /**
     * 查询数据源管理列表
     *
     * @param erDatasource 数据源管理
     * @return 数据源管理
     */
    @Override
    public List<ErDatasource> selectErDatasourceList(ErDatasource erDatasource)
    {
        return erDatasourceMapper.selectErDatasourceList(erDatasource);
    }

    /**
     * 新增数据源管理
     *
     * @param erDatasource 数据源管理
     * @return 结果
     */
    @Override
    public int insertErDatasource(ErDatasource erDatasource)
    {
        // 自动构建JDBC URL和驱动类
        buildJdbcInfo(erDatasource);
        erDatasource.setCreateTime(DateUtils.getNowDate());
        return erDatasourceMapper.insertErDatasource(erDatasource);
    }

    /**
     * 修改数据源管理
     *
     * @param erDatasource 数据源管理
     * @return 结果
     */
    @Override
    public int updateErDatasource(ErDatasource erDatasource)
    {
        // 自动构建JDBC URL和驱动类
        buildJdbcInfo(erDatasource);
        erDatasource.setUpdateTime(DateUtils.getNowDate());
        return erDatasourceMapper.updateErDatasource(erDatasource);
    }

    /**
     * 批量删除数据源管理
     *
     * @param datasourceIds 需要删除的数据源管理主键
     * @return 结果
     */
    @Override
    public int deleteErDatasourceByDatasourceIds(Long[] datasourceIds)
    {
        return erDatasourceMapper.deleteErDatasourceByDatasourceIds(datasourceIds);
    }

    /**
     * 删除数据源管理信息
     *
     * @param datasourceId 数据源管理主键
     * @return 结果
     */
    @Override
    public int deleteErDatasourceByDatasourceId(Long datasourceId)
    {
        return erDatasourceMapper.deleteErDatasourceByDatasourceId(datasourceId);
    }

    /**
     * 校验数据源名称是否唯一
     *
     * @param erDatasource 数据源信息
     * @return 结果
     */
    @Override
    public boolean checkDatasourceNameUnique(ErDatasource erDatasource)
    {
        Long datasourceId = StringUtils.isNull(erDatasource.getDatasourceId()) ? -1L : erDatasource.getDatasourceId();
        ErDatasource info = erDatasourceMapper.checkDatasourceNameUnique(erDatasource.getDatasourceName());
        if (StringUtils.isNotNull(info) && info.getDatasourceId().longValue() != datasourceId.longValue())
        {
            return false;
        }
        return true;
    }

    /**
     * 测试数据源连接
     *
     * @param erDatasource 数据源信息
     * @return 结果
     */
    @Override
    public String testConnection(ErDatasource erDatasource)
    {
        Connection conn = null;
        try
        {
            // 自动构建JDBC URL和驱动类
            buildJdbcInfo(erDatasource);

            // 加载驱动
            Class.forName(erDatasource.getDriverClass());

            // 获取连接
            conn = DriverManager.getConnection(
                erDatasource.getJdbcUrl(),
                erDatasource.getUsername(),
                erDatasource.getPassword()
            );

            // 测试连接
            if (conn != null && !conn.isClosed())
            {
                // 更新测试状态
                if (erDatasource.getDatasourceId() != null)
                {
                    ErDatasource updateDatasource = new ErDatasource();
                    updateDatasource.setDatasourceId(erDatasource.getDatasourceId());
                    updateDatasource.setTestStatus("0");
                    updateDatasource.setTestMessage("连接成功");
                    updateDatasource.setTestTime(new Date());
                    erDatasourceMapper.updateErDatasource(updateDatasource);
                }
                return "连接成功";
            }
            else
            {
                return "连接失败：无法建立连接";
            }
        }
        catch (ClassNotFoundException e)
        {
            String errorMsg = "驱动类未找到：" + e.getMessage();
            updateTestStatus(erDatasource.getDatasourceId(), "1", errorMsg);
            return errorMsg;
        }
        catch (SQLException e)
        {
            String errorMsg = "连接失败：" + e.getMessage();
            updateTestStatus(erDatasource.getDatasourceId(), "1", errorMsg);
            return errorMsg;
        }
        catch (Exception e)
        {
            String errorMsg = "测试异常：" + e.getMessage();
            updateTestStatus(erDatasource.getDatasourceId(), "1", errorMsg);
            return errorMsg;
        }
        finally
        {
            if (conn != null)
            {
                try
                {
                    conn.close();
                }
                catch (SQLException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 更新测试状态
     */
    private void updateTestStatus(Long datasourceId, String testStatus, String testMessage)
    {
        if (datasourceId != null)
        {
            ErDatasource updateDatasource = new ErDatasource();
            updateDatasource.setDatasourceId(datasourceId);
            updateDatasource.setTestStatus(testStatus);
            updateDatasource.setTestMessage(testMessage);
            updateDatasource.setTestTime(new Date());
            erDatasourceMapper.updateErDatasource(updateDatasource);
        }
    }

    /**
     * 根据数据源类型自动构建JDBC URL和驱动类
     */
    private void buildJdbcInfo(ErDatasource erDatasource)
    {
        if (StringUtils.isEmpty(erDatasource.getJdbcUrl()))
        {
            String jdbcUrl = buildJdbcUrl(erDatasource);
            erDatasource.setJdbcUrl(jdbcUrl);
        }

        if (StringUtils.isEmpty(erDatasource.getDriverClass()))
        {
            String driverClass = getDriverClass(erDatasource.getDatasourceType());
            erDatasource.setDriverClass(driverClass);
        }
    }

    /**
     * 构建JDBC URL
     */
    private String buildJdbcUrl(ErDatasource erDatasource)
    {
        String type = erDatasource.getDatasourceType();
        String host = erDatasource.getHost();
        Integer port = erDatasource.getPort();
        String database = erDatasource.getDatabaseName();
        String params = StringUtils.isEmpty(erDatasource.getConnectionParams()) ? "" : erDatasource.getConnectionParams();

        StringBuilder jdbcUrl = new StringBuilder();

        switch (type.toUpperCase())
        {
            case "MYSQL":
            case "TIDB":
                jdbcUrl.append("jdbc:mysql://").append(host).append(":").append(port).append("/").append(database);
                if (StringUtils.isNotEmpty(params) && !params.equals("{}"))
                {
                    jdbcUrl.append("?").append(params);
                }
                else
                {
                    jdbcUrl.append("?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8");
                }
                break;
            case "POSTGRESQL":
                jdbcUrl.append("jdbc:postgresql://").append(host).append(":").append(port).append("/").append(database);
                if (StringUtils.isNotEmpty(params) && !params.equals("{}"))
                {
                    jdbcUrl.append("?").append(params);
                }
                break;
            case "STARROCKS":
                jdbcUrl.append("jdbc:mysql://").append(host).append(":").append(port).append("/").append(database);
                if (StringUtils.isNotEmpty(params) && !params.equals("{}"))
                {
                    jdbcUrl.append("?").append(params);
                }
                break;
            case "ORACLE":
                jdbcUrl.append("jdbc:oracle:thin:@").append(host).append(":").append(port).append(":").append(database);
                break;
            case "SQLSERVER":
                jdbcUrl.append("jdbc:sqlserver://").append(host).append(":").append(port).append(";DatabaseName=").append(database);
                break;
            default:
                jdbcUrl.append("jdbc:mysql://").append(host).append(":").append(port).append("/").append(database);
        }

        return jdbcUrl.toString();
    }

    /**
     * 获取驱动类名
     */
    private String getDriverClass(String datasourceType)
    {
        switch (datasourceType.toUpperCase())
        {
            case "MYSQL":
            case "TIDB":
            case "STARROCKS":
                return "com.mysql.cj.jdbc.Driver";
            case "POSTGRESQL":
                return "org.postgresql.Driver";
            case "ORACLE":
                return "oracle.jdbc.driver.OracleDriver";
            case "SQLSERVER":
                return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default:
                return "com.mysql.cj.jdbc.Driver";
        }
    }
}
