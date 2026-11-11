package cn.easyreport.sync.dm;

import cn.easyreport.sync.DataSourceConnector;
import cn.easyreport.sync.domain.ErDatasource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** DM8 connector. Identifiers retain the case reported by JDBC. */
public class DmConnector implements DataSourceConnector {
    private static final Logger log = LoggerFactory.getLogger(DmConnector.class);
    private final ErDatasource datasource;
    private final Connection connection;

    public DmConnector(ErDatasource datasource) throws Exception {
        this.datasource = datasource;
        Class.forName(datasource.getDriverClass());
        connection = DriverManager.getConnection(datasource.getJdbcUrl(), datasource.getUsername(), datasource.getPassword());
    }

    @Override
    public List<String> listTables(String schemaPattern, String tablePattern) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = schemaPattern == null || schemaPattern.trim().isEmpty()
            ? DmJdbcSupport.escapePattern(metadata, DmJdbcSupport.currentSchema(connection)) : schemaPattern;
        TreeSet<String> names = new TreeSet<>();
        String patterns = tablePattern == null || tablePattern.trim().isEmpty() ? "%" : tablePattern;
        for (String pattern : patterns.split("[,;]")) {
            if (pattern.trim().isEmpty()) continue;
            try (ResultSet rows = metadata.getTables(null, schema, pattern.trim(), new String[]{"TABLE"})) {
                while (rows.next()) names.add(rows.getString("TABLE_NAME"));
            }
        }
        return new ArrayList<>(names);
    }

    @Override
    public boolean tableExists(String schemaPattern, String tableName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = schemaPattern == null || schemaPattern.isEmpty()
            ? DmJdbcSupport.currentSchema(connection) : schemaPattern;
        try (ResultSet rows = metadata.getTables(null, DmJdbcSupport.escapePattern(metadata, schema),
                DmJdbcSupport.escapePattern(metadata, tableName), new String[]{"TABLE"})) {
            while (rows.next()) {
                if (tableName.equals(rows.getString("TABLE_NAME")) && schema.equals(rows.getString("TABLE_SCHEM"))) return true;
            }
            return false;
        }
    }

    @Override
    public String getCreateTableSql(String schemaPattern, String tableName) {
        return "-- DM8 uses DmSchemaExtractor and DmDialect for DDL";
    }

    @Override
    public void execute(String sql) throws Exception {
        log.debug("Executing Dm SQL: {}", sql);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** DM schemas are independent objects, not users. Do not invent credentials or create users. */
    public void ensureSchemaExists(String schema) throws Exception {
        if (schema == null || schema.isEmpty()) return;
        try (ResultSet rows = connection.getMetaData().getSchemas()) {
            while (rows.next()) {
                if (schema.equals(rows.getString("TABLE_SCHEM"))) return;
            }
        }
        execute("CREATE SCHEMA " + DmJdbcSupport.quoteIdentifier(schema));
    }

    @Override
    public String getDatabaseName() {
        return datasource.getDatabaseName();
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("Closing Dm connection failed", e);
        }
    }
}
