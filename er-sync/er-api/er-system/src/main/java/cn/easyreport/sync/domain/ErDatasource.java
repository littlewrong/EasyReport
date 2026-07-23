package cn.easyreport.sync.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import cn.easyreport.common.annotation.Excel;
import cn.easyreport.common.core.domain.BaseEntity;

/**
 * 数据源管理对象 er_datasource
 *
 * @author easyreport
 * @date 2026-01-16
 */
public class ErDatasource extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 数据源ID */
    private Long datasourceId;

    /** 数据源名称 */
    @Excel(name = "数据源名称")
    private String datasourceName;

    /** 数据源类型（MYSQL、POSTGRESQL、TIDB、STARROCKS、ORACLE、SQLSERVER等） */
    @Excel(name = "数据源类型")
    private String datasourceType;

    /** 主机地址 */
    @Excel(name = "主机地址")
    private String host;

    /** 端口号 */
    @Excel(name = "端口号")
    private Integer port;

    /** 数据库名 */
    @Excel(name = "数据库名")
    private String databaseName;

    /** 用户名 */
    @Excel(name = "用户名")
    private String username;

    /** 密码（加密存储） */
    private String password;

    /** 驱动类名 */
    private String driverClass;

    /** JDBC连接URL */
    private String jdbcUrl;

    /** 连接参数（JSON格式） */
    private String connectionParams;

    /** 状态（0正常 1停用） */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    private String status;

    /** 测试状态（0连接成功 1连接失败 2未测试） */
    @Excel(name = "测试状态", readConverterExp = "0=连接成功,1=连接失败,2=未测试")
    private String testStatus;

    /** 测试消息 */
    private String testMessage;

    /** 最后测试时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date testTime;

    public void setDatasourceId(Long datasourceId)
    {
        this.datasourceId = datasourceId;
    }

    public Long getDatasourceId()
    {
        return datasourceId;
    }

    public void setDatasourceName(String datasourceName)
    {
        this.datasourceName = datasourceName;
    }

    public String getDatasourceName()
    {
        return datasourceName;
    }

    public void setDatasourceType(String datasourceType)
    {
        this.datasourceType = datasourceType;
    }

    public String getDatasourceType()
    {
        return datasourceType;
    }

    public void setHost(String host)
    {
        this.host = host;
    }

    public String getHost()
    {
        return host;
    }

    public void setPort(Integer port)
    {
        this.port = port;
    }

    public Integer getPort()
    {
        return port;
    }

    public void setDatabaseName(String databaseName)
    {
        this.databaseName = databaseName;
    }

    public String getDatabaseName()
    {
        return databaseName;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public String getUsername()
    {
        return username;
    }

    public void setPassword(String password)
    {
        this.password = password;
    }

    public String getPassword()
    {
        return password;
    }

    public void setDriverClass(String driverClass)
    {
        this.driverClass = driverClass;
    }

    public String getDriverClass()
    {
        return driverClass;
    }

    public void setJdbcUrl(String jdbcUrl)
    {
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUrl()
    {
        return jdbcUrl;
    }

    public void setConnectionParams(String connectionParams)
    {
        this.connectionParams = connectionParams;
    }

    public String getConnectionParams()
    {
        return connectionParams;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    public void setTestStatus(String testStatus)
    {
        this.testStatus = testStatus;
    }

    public String getTestStatus()
    {
        return testStatus;
    }

    public void setTestMessage(String testMessage)
    {
        this.testMessage = testMessage;
    }

    public String getTestMessage()
    {
        return testMessage;
    }

    public void setTestTime(Date testTime)
    {
        this.testTime = testTime;
    }

    public Date getTestTime()
    {
        return testTime;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("datasourceId", getDatasourceId())
            .append("datasourceName", getDatasourceName())
            .append("datasourceType", getDatasourceType())
            .append("host", getHost())
            .append("port", getPort())
            .append("databaseName", getDatabaseName())
            .append("username", getUsername())
            .append("password", getPassword())
            .append("driverClass", getDriverClass())
            .append("jdbcUrl", getJdbcUrl())
            .append("connectionParams", getConnectionParams())
            .append("status", getStatus())
            .append("testStatus", getTestStatus())
            .append("testMessage", getTestMessage())
            .append("testTime", getTestTime())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
