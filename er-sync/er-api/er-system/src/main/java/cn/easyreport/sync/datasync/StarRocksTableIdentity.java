package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;

import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only identity checks shared by StarRocks initialization import paths. */
final class StarRocksTableIdentity {
    private static final String IDENTIFIER = "(?:`(?:``|[^`])+`|[a-zA-Z0-9_$\\u0080-\\uFFFF]+)";
    private static final Pattern TABLE = Pattern.compile("^(" + IDENTIFIER + ")(?:\\.(" + IDENTIFIER + "))?$");

    private StarRocksTableIdentity() {
    }

    static void verifyDistinct(ErDatasource datasource, Connection source, Connection target,
            String schema, String table, String targetTable, String catalogJdbcUrl,
            DataSyncCancellation cancellation) throws SQLException {
        stopped(cancellation);
        Matcher names = TABLE.matcher(targetTable == null ? "" : targetTable);
        if (!names.matches()) {
            throw new IllegalArgumentException("无法确认 StarRocks 目标表名称，已取消初始化，尚未清空目标表");
        }
        String destinationTable = unquote(names.group(2) == null ? names.group(1) : names.group(2));
        if (!destinationTable.equals(table)) return;
        String sourceSchema = first(schema);
        if (sourceSchema == null) sourceSchema = first(currentCatalog(source), datasource.getDatabaseName());
        String destinationSchema = names.group(2) == null ? currentCatalog(target) : unquote(names.group(1));
        if (sourceSchema == null || destinationSchema == null || destinationSchema.trim().isEmpty()) {
            throw new IllegalArgumentException("无法确认 StarRocks 源和目标数据库，不能初始化到同名表，尚未清空目标表");
        }
        if (!sourceSchema.equals(destinationSchema)) return;

        Endpoint destination;
        try {
            DatabaseMetaData metadata = target.getMetaData();
            destination = Endpoint.parse(metadata == null ? null : metadata.getURL());
        } catch (SQLException failure) {
            stopped(cancellation);
            throw safe("无法核对 StarRocks 目标连接地址，尚未清空目标表", failure);
        }
        boolean sameEndpoint = destination != null
                && (destination.same(Endpoint.parse(datasource.getJdbcUrl()))
                    || destination.same(Endpoint.parse(catalogJdbcUrl)));
        // Different FE nodes, DNS aliases and public/private addresses can serve one cluster.
        if (sameEndpoint || clusterId(source, cancellation) == clusterId(target, cancellation)) {
            throw new IllegalArgumentException("StarRocks 源表与目标表为同一表，初始化清空会删除源数据，请更换目标表，尚未清空目标表");
        }
    }

    private static long clusterId(Connection connection, DataSyncCancellation cancellation) throws SQLException {
        stopped(cancellation);
        Long identity = null;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SHOW FRONTENDS")) {
            while (rows.next()) {
                stopped(cancellation);
                long value = rows.getLong("ClusterId");
                if (rows.wasNull() || value <= 0 || (identity != null && identity != value)) {
                    throw new IllegalArgumentException("无法确认 StarRocks 集群身份，不能初始化到同名库表，尚未清空目标表");
                }
                identity = value;
            }
        } catch (SQLException failure) {
            stopped(cancellation);
            // Driver diagnostics can include connection properties; keep only safe error codes.
            throw safe("源和目标 StarRocks 库表同名，需要两端账号具有读取 SHOW FRONTENDS 的权限以核对集群身份；"
                    + "也可改用不同目标库或表名，尚未清空目标表", failure);
        }
        if (identity == null) {
            throw new IllegalArgumentException("无法读取 StarRocks 集群身份，不能初始化到同名库表，尚未清空目标表");
        }
        return identity;
    }

    private static void stopped(DataSyncCancellation cancellation) throws DataSyncCancellation.SyncStoppedException {
        if (cancellation != null) cancellation.checkStopped();
    }

    private static String currentCatalog(Connection connection) throws SQLException {
        try {
            return connection.getCatalog();
        } catch (SQLException failure) {
            throw safe("无法确认 StarRocks 当前数据库，尚未清空目标表", failure);
        }
    }

    private static SQLException safe(String operation, SQLException failure) {
        if (failure instanceof DataSyncCancellation.SyncStoppedException) return failure;
        return new IdentitySQLException(operation, failure);
    }

    static final class IdentitySQLException extends SQLException {
        IdentitySQLException(String operation, SQLException failure) {
            super(operation + "（SQLState=" + failure.getSQLState() + ", errorCode="
                    + failure.getErrorCode() + "）", failure.getSQLState(), failure.getErrorCode());
        }
    }

    private static String unquote(String identifier) {
        return identifier.startsWith("`") ? identifier.substring(1, identifier.length() - 1).replace("``", "`") : identifier;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return null;
    }

    private static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        boolean same(Endpoint other) {
            return other != null && host.equalsIgnoreCase(other.host) && port == other.port;
        }

        static Endpoint parse(String url) {
            try {
                if (url == null || !url.regionMatches(true, 0, "jdbc:mysql:", 0, 11)) return null;
                URI uri = URI.create("source:" + url.substring(11));
                if (uri.getHost() == null || uri.getUserInfo() != null) return null;
                // Connector/J uses 3306 unless the StarRocks FE port is explicit in the URL.
                int port = uri.getPort() < 0 ? 3306 : uri.getPort();
                return port > 0 && port <= 65535 ? new Endpoint(uri.getHost(), port) : null;
            } catch (IllegalArgumentException invalid) {
                // Unusual driver URLs are still checked through actual server cluster IDs.
                return null;
            }
        }
    }
}
