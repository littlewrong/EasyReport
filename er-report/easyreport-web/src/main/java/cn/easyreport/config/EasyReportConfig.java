package cn.easyreport.config;

import cn.easyreport.console.UReportServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

/**
 * @author ChainStrong
 */
@ImportResource("classpath:context.xml")
@Configuration
public class EasyReportConfig {
    @Bean
    public ServletRegistrationBean buildUReportServlet() {
        return new ServletRegistrationBean(new UReportServlet(), "/easyreport/*");
    }
}
