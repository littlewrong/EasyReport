package cn.easyreport.sync.datasync;

import cn.easyreport.sync.domain.ErDatasource;

import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Checks settings which can make source statistics and Catalog scans see different rows or dates. */
final class StarRocksCatalogSessionSettings {
    private static final String TIMEZONE = "connectiontimezone";
    private static final Set<String> CREDENTIAL_SETTINGS = new HashSet<>(Arrays.asList(
            "user", "username", "password", "password1", "password2", "password3"));
    private static final Set<String> SENSITIVE_SETTINGS = new HashSet<>(Arrays.asList(
            "sessionvariables", "forceconnectiontimezonetosession", TIMEZONE, "preserveinstants",
            "connectioninitsql", "connectioninitsqls", "initsql", "initsqls", "init_sql", "initializationsql",
            "options", "currentschema", "searchpath", "role", "currentuser", "proxy_client_name", "internal_logon",
            "user", "username", "password", "password1", "password2", "password3",
            "host", "port", "address", "protocol", "dbname", "database", "databasename",
            "socketfactory", "socketfactoryarg", "socketfactoryclassname", "socksproxyhost", "socksproxyport",
            "localsocketaddress", "dnssrv", "dnssrvservicename", "propertiestransform", "useconfigs",
            "connectionlifecycleinterceptors", "queryinterceptors", "authenticationplugins",
            "defaultauthenticationplugin", "authenticationscheme", "integratedsecurity", "domain",
            "jaasconfigurationname", "jaasapplicationname", "gsslib", "kerberosservername"));

    private StarRocksCatalogSessionSettings() { }

    static void verify(Connection source, ErDatasource datasource, Map<String, String> catalogProperties,
            DataSyncCancellation cancellation) throws Exception {
        stopped(cancellation);
        Settings settings = settings(source, datasource, catalogProperties, cancellation);
        for (String name : SENSITIVE_SETTINGS) {
            // Each connection owns its credentials. Still compare configured versus actual
            // source credentials in settings(), but do not require the Catalog account to match.
            if (CREDENTIAL_SETTINGS.contains(name)) continue;
            String sourceValue = value(settings.source, name);
            String catalogValue = value(settings.catalog, name);
            // Driver timezone labels alone do not identify a MySQL session's effective timezone.
            // Resolve SYSTEM and compare the data's date range only when a TIMESTAMP is actually used.
            if (TIMEZONE.equals(name) && (settings.mysql || "STARROCKS".equalsIgnoreCase(datasource.getDatasourceType()))) continue;
            if (!equal(sourceValue, catalogValue)) throw mismatch(name);
        }
        stopped(cancellation);
    }

    /** Call only for a MySQL/TiDB TIMESTAMP field; ordinary DATE/DATETIME needs no timezone query. */
    static void verifyTimestampSession(Connection source, ErDatasource datasource,
            Map<String, String> catalogProperties, DataSyncCancellation cancellation) throws Exception {
        LocalDate today = LocalDate.now();
        verifyTimestampSession(source, datasource, catalogProperties, cancellation, today, today);
    }

    static void verifyTimestampSession(Connection source, ErDatasource datasource,
            Map<String, String> catalogProperties, DataSyncCancellation cancellation,
            LocalDate firstDay, LocalDate lastDay) throws Exception {
        verify(source, datasource, catalogProperties, cancellation);
        Settings settings = settings(source, datasource, catalogProperties, cancellation);
        if (!settings.mysql) return;
        if (firstDay == null || lastDay == null || firstDay.isAfter(lastDay)) {
            throw invalid("无法确认 TIMESTAMP 数据日期范围");
        }
        stopped(cancellation);
        String sessionZone;
        String globalZone;
        String systemZone;
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT @@session.time_zone, @@global.time_zone, @@system_time_zone")) {
            if (!rows.next()) throw invalid("无法读取源 TIMESTAMP 会话时区");
            sessionZone = rows.getString(1);
            globalZone = rows.getString(2);
            systemZone = rows.getString(3);
        }
        stopped(cancellation);
        if (sessionZone == null || globalZone == null) throw invalid("无法确认源 TIMESTAMP 会话时区");
        sessionZone = resolveServerZone(sessionZone, systemZone);
        globalZone = resolveServerZone(globalZone, systemZone);
        String sourceZone = value(settings.source, TIMEZONE);
        String catalogZone = value(settings.catalog, TIMEZONE);
        if ("true".equals(value(settings.source, "forceconnectiontimezonetosession"))
                && (sourceZone == null || "LOCAL".equalsIgnoreCase(sourceZone))) {
            throw invalid("TIMESTAMP 强制会话时区不能依赖两台服务器的本地时区；请在两端 JDBC URL 显式配置相同的 connectionTimeZone/serverTimezone");
        }
        boolean explicitlySharedSession = settings.source.containsKey("sessionvariables")
                || "true".equals(value(settings.source, "forceconnectiontimezonetosession"));
        if (!explicitlySharedSession && !sameTimezone(sessionZone, globalZone, firstDay, lastDay)) {
            throw invalid("源 TIMESTAMP 会话时区与全局时区不同，Catalog 无法确认复用；请统一两端 JDBC 会话设置");
        }
        // An unconfigured Catalog inherits the source server's session default. SYSTEM is resolved
        // from @@system_time_zone, never from the EasyReport host's JVM or an ambiguous abbreviation.
        String catalogSession = explicitlySharedSession ? sessionZone : globalZone;
        String sourceEffective = effectiveDriverZone(sourceZone, sessionZone, systemZone);
        String catalogEffective = effectiveDriverZone(catalogZone, catalogSession, systemZone);
        if (!sameTimezone(sourceEffective, catalogEffective, firstDay, lastDay)) {
            throw invalid("源与 Catalog 的 TIMESTAMP 时区在数据日期范围内不一致或无法确认一致；"
                    + "请在两端 JDBC URL 显式配置相同的 connectionTimeZone/serverTimezone");
        }
        if ("true".equals(value(settings.source, "forceconnectiontimezonetosession"))
                && sourceZone != null && !"SERVER".equalsIgnoreCase(sourceZone)
                && !"LOCAL".equalsIgnoreCase(sourceZone)
                && !sameTimezone(sourceEffective, sessionZone, firstDay, lastDay)) {
            throw invalid("源 TIMESTAMP 实际会话时区与 JDBC 强制时区设置不一致");
        }
        stopped(cancellation);
    }

    private static Settings settings(Connection source, ErDatasource datasource,
            Map<String, String> catalogProperties, DataSyncCancellation cancellation) throws Exception {
        if (source == null || datasource == null || catalogProperties == null) throw invalid("缺少 Catalog 会话校验配置");
        Map<String, String> configured = parameters(datasource.getJdbcUrl());
        stopped(cancellation);
        // getConnectionParams may already have been appended by the datasource service. The URL of
        // the live connection is authoritative; never reconstruct it from a potentially stale field.
        String actualUrl = source.getMetaData().getURL();
        stopped(cancellation);
        Map<String, String> actual = actualUrl == null || actualUrl.trim().isEmpty()
                ? configured : parameters(actualUrl);
        for (String name : SENSITIVE_SETTINGS) {
            if (configured.containsKey(name) && !equal(value(configured, name), value(actual, name))) {
                throw invalid("源 JDBC 实际连接设置与配置不一致，请确认连接初始化参数");
            }
        }
        String type = datasource.getDatasourceType();
        boolean mysql = "MYSQL".equalsIgnoreCase(type) || "TIDB".equalsIgnoreCase(type);
        return new Settings(actual, parameters(catalogProperties.get("jdbc_uri")), mysql);
    }

    private static Map<String, String> parameters(String url) {
        Map<String, String> result = new HashMap<>();
        if (url == null) throw invalid("缺少 JDBC URL，无法核对 Catalog 会话设置");
        if (url.regionMatches(true, 0, "jdbc:sqlserver:", 0, "jdbc:sqlserver:".length())) {
            int start = url.indexOf(';');
            if (start >= 0) parseSqlServerProperties(url.substring(start + 1), result);
        } else {
            int start = url.indexOf('?');
            if (start >= 0) {
                for (String property : url.substring(start + 1).split("&", -1)) {
                    if (property.isEmpty()) continue;
                    int separator = property.indexOf('=');
                    if (separator < 0) throw invalid("JDBC URL 参数格式无法安全核对");
                    put(result, decode(property.substring(0, separator)), decode(property.substring(separator + 1)));
                }
            }
        }
        return result;
    }

    private static void parseSqlServerProperties(String properties, Map<String, String> result) {
        int cursor = 0;
        while (cursor < properties.length()) {
            if (properties.charAt(cursor) == ';') { cursor++; continue; }
            int equals = properties.indexOf('=', cursor);
            if (equals < 0) throw invalid("SQL Server JDBC 参数格式无法安全核对");
            String name = properties.substring(cursor, equals).trim();
            cursor = equals + 1;
            StringBuilder value = new StringBuilder();
            if (cursor < properties.length() && properties.charAt(cursor) == '{') {
                cursor++;
                boolean closed = false;
                while (cursor < properties.length()) {
                    char ch = properties.charAt(cursor++);
                    if (ch == '}') {
                        if (cursor < properties.length() && properties.charAt(cursor) == '}') { value.append('}'); cursor++; }
                        else { closed = true; break; }
                    } else value.append(ch);
                }
                if (!closed || (cursor < properties.length() && properties.charAt(cursor) != ';')) {
                    throw invalid("SQL Server JDBC 转义参数无法安全核对");
                }
            } else {
                int end = properties.indexOf(';', cursor);
                if (end < 0) end = properties.length();
                value.append(properties, cursor, end);
                cursor = end;
            }
            put(result, name, value.toString());
        }
    }

    private static void put(Map<String, String> parameters, String name, String value) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if ("servertimezone".equals(key)) key = TIMEZONE;
        if (!SENSITIVE_SETTINGS.contains(key)) return;
        String normalized = normalize(key, value);
        String previous = parameters.put(key, normalized);
        if (previous != null && !previous.equals(normalized)) throw invalid("JDBC URL 包含冲突的会话或身份参数");
    }

    private static String normalize(String name, String value) {
        String normalized = value.trim();
        if (TIMEZONE.equals(name)) return zone(normalized);
        if ("forceconnectiontimezonetosession".equals(name) || "preserveinstants".equals(name)
                || "integratedsecurity".equals(name) || "dnssrv".equals(name)) {
            if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized) || "yes".equalsIgnoreCase(normalized)) return "true";
            if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized) || "no".equalsIgnoreCase(normalized)) return "false";
        }
        return normalized;
    }

    private static String value(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value != null) return value;
        if ("forceconnectiontimezonetosession".equals(key)) return "false";
        if ("preserveinstants".equals(key)) return "true";
        return null;
    }

    private static String zone(String value) {
        String normalized = value.trim();
        if ("SERVER".equalsIgnoreCase(normalized) || "LOCAL".equalsIgnoreCase(normalized)
                || "SYSTEM".equalsIgnoreCase(normalized)) return normalized.toUpperCase(Locale.ROOT);
        try {
            ZoneId zone = ZoneId.of(normalized);
            return zone.getRules().isFixedOffset() ? zone.getRules().getOffset(Instant.EPOCH).getId() : zone.getId();
        } catch (RuntimeException unknown) {
            return normalized;
        }
    }

    private static String resolveServerZone(String value, String systemZone) {
        return "SYSTEM".equalsIgnoreCase(value) && systemZone != null && !systemZone.trim().isEmpty()
                ? zone(systemZone) : zone(value);
    }

    private static String effectiveDriverZone(String driverZone, String sessionZone, String systemZone) {
        return driverZone == null || "SERVER".equalsIgnoreCase(driverZone)
                ? sessionZone : resolveServerZone(driverZone, systemZone);
    }

    private static boolean sameTimezone(String left, String right, LocalDate firstDay, LocalDate lastDay) {
        if (left == null || right == null || "LOCAL".equalsIgnoreCase(left) || "LOCAL".equalsIgnoreCase(right)) return false;
        if (left.equals(right)) return true;
        try {
            ZoneRules first = ZoneId.of(left).getRules();
            ZoneRules second = ZoneId.of(right).getRules();
            if (first.equals(second)) return true;
            // Include every offset change in the complete source range. Matching today's offset
            // alone would incorrectly equate fixed GMT+8 and Shanghai during historical DST.
            Instant cursor = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(86_400);
            Instant end = lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(86_400);
            while (cursor.isBefore(end)) {
                if (!first.getOffset(cursor).equals(second.getOffset(cursor))) return false;
                ZoneOffsetTransition a = first.nextTransition(cursor);
                ZoneOffsetTransition b = second.nextTransition(cursor);
                Instant next = a == null ? (b == null ? end : b.getInstant())
                        : b == null || a.getInstant().isBefore(b.getInstant()) ? a.getInstant() : b.getInstant();
                if (!next.isBefore(end)) return true;
                cursor = next.plusNanos(1);
            }
            return true;
        } catch (RuntimeException unknown) {
            return false;
        }
    }
    private static boolean equal(String left, String right) { return left == null ? right == null : left.equals(right); }
    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception invalid) { throw invalid("JDBC URL 参数编码无法安全核对"); }
    }
    private static IllegalArgumentException mismatch(String name) {
        return invalid("源与 Catalog 的 JDBC 会话或身份参数不一致（" + name + "），请统一两端配置");
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message + "，尚未清空目标表");
    }
    private static void stopped(DataSyncCancellation cancellation) throws DataSyncCancellation.SyncStoppedException {
        if (cancellation != null) cancellation.checkStopped();
    }

    private static final class Settings {
        final Map<String, String> source;
        final Map<String, String> catalog;
        final boolean mysql;
        Settings(Map<String, String> source, Map<String, String> catalog, boolean mysql) {
            this.source = source;
            this.catalog = catalog;
            this.mysql = mysql;
        }
    }
}
