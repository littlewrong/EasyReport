package cn.easyreport.sync.sqlserver;

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
 * SQL Server connector using JDBC.
 */
public class SqlServerConnector implements DataSourceConnector {

    private static final Logger log = LoggerFactory.getLogger(SqlServerConnector.class);

    private final ErDatasource datasource;
    private Connection conn;

    public SqlServerConnector(ErDatasource datasource) throws Exception {
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

        // SQL Server 默认 schema 是 dbo
        String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : "dbo";

        if (!containsWildcard(tablePattern) && !containsWildcard(schema)) {
            // Handle explicit table names (comma/semicolon/space separated)
            String[] names = tablePattern.split("[;,\\s]+");
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                try (ResultSet rs = meta.getTables(null, schema, name, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"));
                    }
                }
            }
        } else {
            // Handle wildcard patterns
            try (ResultSet rs = meta.getTables(null, schema, tablePattern, new String[]{"TABLE"})) {
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
        String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : "dbo";
        try (ResultSet rs = meta.getTables(null, schema, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    @Override
    public String getCreateTableSql(String schemaPattern, String tableName) throws Exception {
        // SQL Server doesn't have a simple SHOW CREATE TABLE command
        // This is a simplified version - production code would need more sophisticated DDL extraction
        throw new UnsupportedOperationException("SQL Server does not support SHOW CREATE TABLE. Use SchemaExtractor instead.");
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
