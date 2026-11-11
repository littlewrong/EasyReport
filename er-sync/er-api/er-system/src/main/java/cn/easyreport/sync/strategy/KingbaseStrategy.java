package cn.easyreport.sync.strategy;

import cn.easyreport.sync.kingbase.KingbaseJdbcSupport;

/** Kingbase schema operations stay within the database selected by the JDBC URL. */
public class KingbaseStrategy implements DatabaseStrategy {
    @Override
    public String getDatabaseType() {
        return "KINGBASE";
    }

    @Override
    public String buildDropTable(String database, String schema, String table) {
        return "DROP TABLE IF EXISTS " + (schema == null || schema.isEmpty() ? "" : quoteIdentifier(schema) + ".")
            + quoteIdentifier(table);
    }

    @Override
    public String buildCreateDatabase(String schemaName) {
        return "CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(schemaName);
    }

    @Override
    public String buildUseDatabase(String databaseName) {
        return null;
    }

    @Override
    public String getIdentifierQuote() {
        return "\"";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return KingbaseJdbcSupport.quoteIdentifier(identifier);
    }

    @Override
    public boolean supportsIfExists() {
        return true;
    }
}
