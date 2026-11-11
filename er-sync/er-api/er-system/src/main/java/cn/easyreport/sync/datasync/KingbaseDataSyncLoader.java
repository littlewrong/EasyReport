package cn.easyreport.sync.datasync;

import cn.easyreport.sync.kingbase.KingbaseJdbcSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/** KingbaseES V9 parameterized INSERT and primary-key ON CONFLICT writes. */
public class KingbaseDataSyncLoader implements DataSyncLoader {

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert) {
        return buildUpsertSql(table, columns, upsert,
            columns.isEmpty() ? Collections.emptyList() : columns.subList(0, 1));
    }

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert, List<String> pkColumns) {
        if (columns == null || columns.isEmpty()) throw new IllegalArgumentException("insert columns are required");
        StringJoiner names = new StringJoiner(",", "(", ")");
        StringJoiner values = new StringJoiner(",", "(", ")");
        for (String column : columns) {
            names.add(quote(column));
            values.add("?");
        }
        String sql = "INSERT INTO " + tableSqlName(table) + names + " OVERRIDING SYSTEM VALUE VALUES " + values;
        if (!upsert) return sql;
        if (pkColumns == null || pkColumns.isEmpty() || !columns.containsAll(pkColumns)) {
            throw new IllegalArgumentException("upsert requires primary key columns present in the insert columns");
        }
        StringJoiner keys = new StringJoiner(",", "(", ")");
        for (String pk : pkColumns) keys.add(quote(pk));
        Set<String> pkSet = new HashSet<>(pkColumns);
        StringJoiner updates = new StringJoiner(",");
        for (String column : columns) {
            if (!pkSet.contains(column)) updates.add(quote(column) + " = EXCLUDED." + quote(column));
        }
        return sql + " ON CONFLICT " + keys
            + (updates.length() == 0 ? " DO NOTHING" : " DO UPDATE SET " + updates);
    }

    @Override
    public String buildDeleteSql(String table, String pkColumn, List<String> ids) {
        if (ids == null || ids.isEmpty()) return "DELETE FROM " + tableSqlName(table) + " WHERE 1=0";
        StringJoiner values = new StringJoiner(",");
        for (String id : ids) values.add("'" + id.replace("'", "''") + "'");
        return "DELETE FROM " + tableSqlName(table) + " WHERE " + quote(pkColumn) + " IN (" + values + ")";
    }

    /** Accept raw or already quoted qualified names without losing dots inside quoted identifiers. */
    static String tableSqlName(String table) {
        if (table == null || table.isEmpty()) throw new IllegalArgumentException("table name is required");
        List<String> parts = new ArrayList<>();
        StringBuilder identifier = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < table.length(); i++) {
            char c = table.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < table.length() && table.charAt(i + 1) == '"') {
                    identifier.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == '.' && !inQuotes) {
                parts.add(quote(identifier.toString()));
                identifier.setLength(0);
            } else {
                identifier.append(c);
            }
        }
        if (inQuotes) throw new IllegalArgumentException("unclosed quote in table name");
        parts.add(quote(identifier.toString()));
        return String.join(".", parts);
    }

    private static String quote(String identifier) {
        return KingbaseJdbcSupport.quoteIdentifier(identifier);
    }
}
