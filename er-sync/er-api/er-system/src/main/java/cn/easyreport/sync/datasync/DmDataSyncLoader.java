package cn.easyreport.sync.datasync;

import cn.easyreport.sync.dm.DmJdbcSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/** DM8 parameterized INSERT/MERGE. Key columns used in ON are never updated. */
public class DmDataSyncLoader implements DataSyncLoader {
    private final Set<String> identityColumns = new HashSet<>();

    @Override
    public void prepareTarget(Connection connection, String table) throws Exception {
        identityColumns.clear();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM " + tableSqlName(table) + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                if (metadata.isAutoIncrement(column)) identityColumns.add(metadata.getColumnName(column));
            }
        }
    }

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert) {
        return buildUpsertSql(table, columns, upsert,
            columns == null || columns.isEmpty() ? Collections.emptyList() : columns.subList(0, 1));
    }

    @Override
    public String buildUpsertSql(String table, List<String> columns, boolean upsert, List<String> pkColumns) {
        if (columns == null || columns.isEmpty()) throw new IllegalArgumentException("insert columns are required");
        String name = tableSqlName(table);
        StringJoiner names = new StringJoiner(",", "(", ")");
        StringJoiner placeholders = new StringJoiner(",", "(", ")");
        for (String column : columns) {
            names.add(quote(column));
            placeholders.add("?");
        }
        if (!upsert) return "INSERT INTO " + name + names + " VALUES " + placeholders;
        if (pkColumns == null || pkColumns.isEmpty() || !columns.containsAll(pkColumns)) {
            throw new IllegalArgumentException("merge requires primary key columns present in the insert columns");
        }
        StringJoiner values = new StringJoiner(", ");
        for (String column : columns) values.add("? AS " + quote(column));
        StringJoiner keys = new StringJoiner(" AND ");
        for (String key : pkColumns) keys.add("t." + quote(key) + " = s." + quote(key));
        Set<String> keySet = new HashSet<>(pkColumns);
        StringJoiner updates = new StringJoiner(",");
        StringJoiner inserted = new StringJoiner(",", "(", ")");
        for (String column : columns) {
            inserted.add("s." + quote(column));
            if (!keySet.contains(column) && !identityColumns.contains(column)) {
                updates.add("t." + quote(column) + " = s." + quote(column));
            }
        }
        return "MERGE INTO " + name + " t USING (SELECT " + values + " FROM DUAL) s ON (" + keys + ")"
            + (updates.length() == 0 ? "" : " WHEN MATCHED THEN UPDATE SET " + updates)
            + " WHEN NOT MATCHED THEN INSERT " + names + " VALUES " + inserted;
    }

    @Override
    public String buildDeleteSql(String table, String pkColumn, List<String> ids) {
        if (ids == null || ids.isEmpty()) return "DELETE FROM " + tableSqlName(table) + " WHERE 1=0";
        StringJoiner values = new StringJoiner(",");
        for (String id : ids) values.add("'" + id.replace("'", "''") + "'");
        return "DELETE FROM " + tableSqlName(table) + " WHERE " + quote(pkColumn) + " IN (" + values + ")";
    }

    static String tableSqlName(String table) {
        List<String> parts = splitTableName(table);
        List<String> quoted = new ArrayList<>();
        for (String part : parts) quoted.add(quote(part));
        return String.join(".", quoted);
    }

    /** Parse quoted names including escaped quotes and dots inside a single identifier. */
    static List<String> splitTableName(String table) {
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
                addPart(parts, identifier);
            } else {
                identifier.append(c);
            }
        }
        if (inQuotes) throw new IllegalArgumentException("unclosed quote in table name");
        addPart(parts, identifier);
        if (parts.size() > 2) throw new IllegalArgumentException("DM table names must be table or schema.table");
        return parts;
    }

    private static void addPart(List<String> parts, StringBuilder identifier) {
        if (identifier.length() == 0) throw new IllegalArgumentException("empty table name component");
        parts.add(identifier.toString());
        identifier.setLength(0);
    }

    private static String quote(String identifier) {
        return DmJdbcSupport.quoteIdentifier(identifier);
    }
}
