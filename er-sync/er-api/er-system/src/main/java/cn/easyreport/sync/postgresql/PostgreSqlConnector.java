package cn.easyreport.sync.postgresql;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.DataSourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 数据库连接器
 *
 * PostgreSQL 特性：
 * 1. 使用双引号作为标识符：SELECT * FROM "schema"."table"
 * 2. 默认 schema 为 public
 * 3. 完全支持标准 SQL
 * 4. 大小写敏感（需要使用双引号）
 */
public class PostgreSqlConnector implements DataSourceConnector {

    private static final Logger log = LoggerFactory.getLogger(PostgreSqlConnector.class);

    private final ErDatasource datasource;
    private Connection conn;

    public PostgreSqlConnector(ErDatasource datasource) throws Exception {
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
        String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : "public";

        DatabaseMetaData meta = conn.getMetaData();
        if (!containsWildcard(tablePattern) && !containsWildcard(schemaPattern)) {
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
        String schema = (schemaPattern != null && !schemaPattern.isEmpty()) ? schemaPattern : "public";
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, schema, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    @Override
    public String getCreateTableSql(String schemaPattern, String tableName) throws Exception {
        // PostgreSQL 没有 SHOW CREATE TABLE 命令，需要从系统表中获取
        // 这里返回一个简单的占位符，实际的 DDL 生成由 PostgreSqlDialect 负责
        return "-- PostgreSQL does not support SHOW CREATE TABLE, use schema extractor instead";
    }

    @Override
    public void execute(String sql) throws Exception {
        log.info("Executing PostgreSQL SQL: {}", sql);
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

    /**
     * 构建完整的表名（带 schema）
     */
    public static String buildFullTableName(String schema, String tableName) {
        String schemaPrefix = (schema != null && !schema.isEmpty()) ? "\"" + schema + "\"." : "\"public\".";
        return schemaPrefix + "\"" + tableName + "\"";
    }

    public Connection getConnection() {
        return conn;
    }
}
