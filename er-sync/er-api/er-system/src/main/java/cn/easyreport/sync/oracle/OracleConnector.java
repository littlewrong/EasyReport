package cn.easyreport.sync.oracle;

import cn.easyreport.sync.domain.ErDatasource;
import cn.easyreport.sync.DataSourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Oracle 数据库连接器
 *
 * Oracle 特性：
 * 1. 使用双引号作为标识符："SCHEMA"."TABLE"
 * 2. Schema 等同于 User，MySQL Database 对应 Oracle Schema(User)
 * 3. 不支持 SHOW CREATE TABLE，DDL 由 OracleDialect 生成
 * 4. 标识符默认大写
 */
public class OracleConnector implements DataSourceConnector {

    private static final Logger log = LoggerFactory.getLogger(OracleConnector.class);

    private final ErDatasource datasource;
    private Connection conn;

    public OracleConnector(ErDatasource datasource) throws Exception {
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
        String schema = resolveSchema(schemaPattern);

        DatabaseMetaData meta = conn.getMetaData();
        if (!containsWildcard(tablePattern) && !containsWildcard(schemaPattern)) {
            String[] names = tablePattern.split("[;,\\s]+");
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                try (ResultSet rs = meta.getTables(null, schema, name.toUpperCase(), new String[]{"TABLE"})) {
                    if (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"));
                    }
                }
            }
        } else {
            try (ResultSet rs = meta.getTables(null, schema, tablePattern != null ? tablePattern.toUpperCase() : "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    list.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return list;
    }

    @Override
    public boolean tableExists(String schemaPattern, String tableName) throws Exception {
        String schema = resolveSchema(schemaPattern);
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, schema, tableName.toUpperCase(), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    @Override
    public String getCreateTableSql(String schemaPattern, String tableName) throws Exception {
        // Oracle 没有 SHOW CREATE TABLE 命令，由 OracleDialect 负责生成 DDL
        return "-- Oracle does not support SHOW CREATE TABLE, use schema extractor instead";
    }

    @Override
    public void execute(String sql) throws Exception {
        log.info("Executing Oracle SQL: {}", sql);
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

    /**
     * 确保 Oracle Schema(User) 存在
     * 当目标 Schema 不存在时自动创建：
     * - 12c+ 使用 NO AUTHENTICATION（纯 Schema，无法登录）
     * - 12c 以下使用随机密码 + ACCOUNT LOCK
     *
     * @param schemaName Schema 名称（将被大写化）
     */
    public void ensureSchemaExists(String schemaName) throws Exception {
        String upperSchema = schemaName.toUpperCase();

        // 检查 Schema(User) 是否已存在
        String checkSql = "SELECT COUNT(*) FROM ALL_USERS WHERE USERNAME = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, upperSchema);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    log.info("Oracle schema(user) '{}' already exists", upperSchema);
                    return;
                }
            }
        }

        log.info("Creating Oracle schema(user) '{}'", upperSchema);

        // 检测 Oracle 版本
        boolean is12cOrLater = isOracle12cOrLater();

        try {
            if (is12cOrLater) {
                // 12c+ 使用 NO AUTHENTICATION，创建纯 Schema 用户
                String createSql = "CREATE USER \"" + upperSchema + "\" NO AUTHENTICATION DEFAULT TABLESPACE USERS";
                try (Statement st = conn.createStatement()) {
                    st.execute(createSql);
                }
            } else {
                // 12c 以下使用随机密码 + ACCOUNT LOCK
                String randomPassword = "P_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                String createSql = "CREATE USER \"" + upperSchema + "\" IDENTIFIED BY \"" + randomPassword + "\" ACCOUNT LOCK DEFAULT TABLESPACE USERS";
                try (Statement st = conn.createStatement()) {
                    st.execute(createSql);
                }
            }

            // 授权：允许使用 USERS 表空间
            String quotaSql = "ALTER USER \"" + upperSchema + "\" QUOTA UNLIMITED ON USERS";
            try (Statement st = conn.createStatement()) {
                st.execute(quotaSql);
            }

            log.info("Successfully created Oracle schema(user) '{}'", upperSchema);
        } catch (SQLException e) {
            if (e.getErrorCode() == 1920) {
                // ORA-01920: user name already exists (并发创建场景)
                log.info("Oracle schema(user) '{}' already exists (concurrent creation)", upperSchema);
            } else {
                throw new RuntimeException(
                    "Failed to create Oracle schema(user) '" + upperSchema + "'. " +
                    "Ensure the connection user has DBA or CREATE USER privilege. " +
                    "Error: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 检测当前连接的 Oracle 是否为 12c 或更高版本
     */
    private boolean isOracle12cOrLater() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT VERSION FROM V$INSTANCE")) {
            if (rs.next()) {
                String version = rs.getString(1);
                log.info("Oracle version: {}", version);
                // 版本格式: 12.1.0.2.0 或 19.0.0.0.0
                String majorStr = version.split("\\.")[0];
                int major = Integer.parseInt(majorStr);
                return major >= 12;
            }
        } catch (Exception e) {
            log.warn("Failed to detect Oracle version from V$INSTANCE, trying PRODUCT_COMPONENT_VERSION", e);
            // 备用查询，不需要 V$INSTANCE 权限
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT VERSION FROM PRODUCT_COMPONENT_VERSION WHERE ROWNUM = 1")) {
                if (rs.next()) {
                    String version = rs.getString(1);
                    log.info("Oracle version (from PRODUCT_COMPONENT_VERSION): {}", version);
                    String majorStr = version.split("\\.")[0];
                    int major = Integer.parseInt(majorStr);
                    return major >= 12;
                }
            } catch (Exception e2) {
                log.warn("Failed to detect Oracle version, defaulting to 12c+ mode", e2);
            }
        }
        // 默认当做 12c+ 处理
        return true;
    }

    private String resolveSchema(String schemaPattern) {
        if (schemaPattern != null && !schemaPattern.isEmpty()) {
            return schemaPattern.toUpperCase();
        }
        // 默认使用连接用户作为 Schema
        return datasource.getUsername() != null ? datasource.getUsername().toUpperCase() : null;
    }

    private boolean containsWildcard(String s) {
        if (s == null) return false;
        return s.contains("%") || s.contains("_");
    }

    /**
     * 构建完整的表名（带 schema）
     */
    public static String buildFullTableName(String schema, String tableName) {
        String schemaPrefix = (schema != null && !schema.isEmpty())
            ? "\"" + schema.toUpperCase() + "\"."
            : "";
        return schemaPrefix + "\"" + tableName.toUpperCase() + "\"";
    }

    public Connection getConnection() {
        return conn;
    }
}
