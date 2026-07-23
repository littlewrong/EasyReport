package cn.easyreport;

import cn.easyreport.sync.registry.DatabaseComponentRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 启动程序
 *
 * @author ruoyi
 */
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class EasyReportApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(EasyReportApplication.class, args);

        // 初始化数据库组件注册中心
        DatabaseComponentRegistry.initialize();

        System.out.println("EasyReport start success");
    }
}
