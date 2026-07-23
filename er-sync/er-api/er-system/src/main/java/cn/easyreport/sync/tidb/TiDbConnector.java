package cn.easyreport.sync.tidb;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.DataSourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * TiDB connector using JDBC. Split为独立实现，便于后续引入 TiDB 特有行为。
 */
public class TiDbConnector implements DataSourceConnector {

    private static final Logger log = LoggerFactory.getLogger(TiDbConnector.class);

    private final ErDatasource datasource;
    private Connection conn;

    public TiDbConnector(ErDatasource datasource) throws Exception {
        this.datasource = datasource;
        Class.forName(datasource.getDriverClass());
        this.conn = DriverManager.getConnection(
            datasource.getJdbcUrl(),
            datasource.getUsername(),
            datasource.getPassword()
        );
    }

    @Override
    public List<String> listTables(String schemaPattern, String tablePattern) throws Exception {
        List<String> list = new ArrayList<>();
        DatabaseMetaData meta = conn.getMetaData();
        if (!containsWildcard(tablePattern) && !containsWildcard(schemaPattern)) {
            String[] names = tablePattern.split("[;,\\s]+");
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                try (ResultSet rs = meta.getTables(schemaPattern, null, name, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"));
                    }
                }
            }
        } else {
            try (ResultSet rs = meta.getTables(schemaPattern, null, tablePattern, new String[]{"TABLE"})) {
                while (rs.next()) {
                    list.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return list;
    }

    @Override
    public boolean tableExists(String schemaPattern, String tableName) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(schemaPattern, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    @Override
    public String getCreateTableSql(String schemaPattern, String tableName) throws Exception {
        String prefix = (schemaPattern != null && !schemaPattern.isEmpty()) ? ("`" + schemaPattern + "`.") : "";
        String sql = "SHOW CREATE TABLE " + prefix + "`" + tableName + "`";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString("Create Table");
            }
        }
        throw new IllegalStateException("Cannot fetch DDL for table: " + tableName);
    }

    @Override
    public void execute(String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    @Override
    public String getDatabaseName() {
        return datasource.getDatabaseName();
    }

    @Override
    public void close() {
        if (conn != null) {
            try { conn.close(); } catch (Exception e) { log.warn("close conn error", e); }
        }
    }

    private boolean containsWildcard(String s) {
        if (s == null) return false;
        return s.contains("%") || s.contains("_");
    }
}
