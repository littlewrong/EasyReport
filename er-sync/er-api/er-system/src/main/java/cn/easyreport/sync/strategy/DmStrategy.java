package cn.easyreport.sync.strategy;

import cn.easyreport.sync.dm.DmJdbcSupport;

/** DM schemas belong to the connected instance; they do not require database switching. */
public class DmStrategy implements DatabaseStrategy {
    @Override
    public String getDatabaseType() { return "DM"; }

    @Override
    public String buildDropTable(String database, String schema, String table) {
        return "DROP TABLE IF EXISTS " + (schema == null || schema.isEmpty() ? "" : quoteIdentifier(schema) + ".")
            + quoteIdentifier(table);
    }

    @Override
    public String buildCreateDatabase(String schemaName) {
        // The executor uses DmConnector.ensureSchemaExists() to check first.
        return "CREATE SCHEMA " + quoteIdentifier(schemaName);
    }

    @Override
    public String buildUseDatabase(String databaseName) { return null; }

    @Override
    public String getIdentifierQuote() { return "\""; }

    @Override
    public String quoteIdentifier(String identifier) { return DmJdbcSupport.quoteIdentifier(identifier); }

    @Override
    public boolean supportsIfExists() { return true; }
}
