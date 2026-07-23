package cn.easyreport.datasource;

import cn.easyreport.definition.datasource.BuildinDatasource;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * @author ChainStrong
 */
@Component
public class DbcpDataSource implements BuildinDatasource {

    @Override
    public String name() {
        return "db_easyreport";
    }

    @Override
    public Connection getConnection() {
        return null;
    }
}
