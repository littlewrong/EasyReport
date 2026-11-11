package cn.easyreport.sync.mapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DM8 source mapping, including DATE clocks in Oracle compatibility mode. */
public class DmTypeMapper implements DatabaseTypeMapper {
    private static final List<String> TARGETS = Arrays.asList("DM", "MYSQL", "TIDB", "STARROCKS", "SQLSERVER", "POSTGRESQL", "ORACLE", "KINGBASE");
    private final KingbaseTypeMapper standardMapper = new KingbaseTypeMapper();

    @Override
    public String getSourceDatabaseType() { return "DM"; }

    @Override
    public boolean supportsTarget(String target) { return target != null && TARGETS.contains(target.toUpperCase(Locale.ROOT)); }

    @Override
    public List<String> getSupportedTargets() { return new ArrayList<>(TARGETS); }

    @Override
    public String mapType(String target, String source) { return resolve(target, source).type; }

    @Override
    public boolean isLossyConversion(String target, String source) { return resolve(target, source).lossy; }

    private DmTargetMappings.Mapping resolve(String destination, String source) {
        if (source == null || !supportsTarget(destination) || "DM".equalsIgnoreCase(destination)) return DmTargetMappings.value(source);
        String target = destination.toUpperCase(Locale.ROOT);
        String type = normalize(source);
        // DM DATE can carry a clock in Oracle compatibility mode. Resource imports
        // cast it on the server, before a JDBC getDate() can discard that clock.
        if ("STARROCKS".equals(target) && "DATE".equals(type)) return DmTargetMappings.value("DATETIME");
        if (type.startsWith("INTERVAL ") || "BFILE".equals(type)) return asText(target);
        if ("NUMERIC".equals(type) && "ORACLE".equals(target)) {
            // Keep numeric keys usable; DM DECIMAL_ENHANCED can retain 40
            // significant digits whereas Oracle NUMBER supports 38.
            return DmTargetMappings.loss("NUMBER");
        }
        if ("KINGBASE".equals(target)) {
            Matcher temporal = Pattern.compile("(TIMESTAMP|TIME)(?:\\((\\d+)\\))?( WITH TIME ZONE)?").matcher(type);
            if (temporal.matches()) {
                int precision = temporal.group(2) == null ? 6 : Integer.parseInt(temporal.group(2));
                String mapped = temporal.group(1) + "(" + Math.min(precision, 6) + ")" + (temporal.group(3) == null ? "" : temporal.group(3));
                return precision > 6 ? DmTargetMappings.loss(mapped) : DmTargetMappings.value(mapped);
            }
        }
        return new DmTargetMappings.Mapping(standardMapper.mapType(target, type), standardMapper.isLossyConversion(target, type));
    }

    private DmTargetMappings.Mapping asText(String target) {
        if ("MYSQL".equals(target) || "TIDB".equals(target)) return DmTargetMappings.loss("LONGTEXT");
        if ("SQLSERVER".equals(target)) return DmTargetMappings.loss("NVARCHAR(MAX)");
        if ("STARROCKS".equals(target)) return DmTargetMappings.loss("STRING");
        if ("ORACLE".equals(target)) return DmTargetMappings.loss("CLOB");
        return DmTargetMappings.loss("TEXT");
    }

    private String normalize(String source) {
        String type = DmTargetMappings.normalize(source).replaceAll("\\((\\d+) (?:CHAR|BYTE)\\)", "($1)");
        if ("DATETIME2".equals(type)) type = "TIMESTAMP(9)";
        if ("DATETIME2_TZ".equals(type)) type = "TIMESTAMP(9) WITH TIME ZONE";
        type = type.replaceFirst("^(?:NUMBER|DECIMAL|DEC)(?=\\(|$)", "NUMERIC")
            .replaceFirst("^(?:NVARCHAR2?|VARCHAR2)(?=\\(|$)", "VARCHAR")
            .replaceFirst("^NCHAR(?=\\(|$)", "CHAR")
            .replaceFirst("^DATETIME2?(?=\\(|$| )", "TIMESTAMP")
            .replaceFirst("^(?:TIME_TZ|TIME WITH LOCAL TIME ZONE)$", "TIME WITH TIME ZONE")
            .replaceFirst("^(?:DATETIME_TZ|TIMESTAMP WITH LOCAL TIME ZONE)$", "TIMESTAMP WITH TIME ZONE");
        if (type.matches("(?:RAW|VARBINARY|BINARY|BLOB|IMAGE|LONGVARBINARY)(?:\\(\\d+\\))?") || "LONG RAW".equals(type)) return "BYTEA";
        if (type.matches("(?:LONGVARCHAR|TEXT|CLOB|NCLOB|LONG)(?:\\(\\d+\\))?")) return "TEXT";
        if (type.matches("(?:BIT|BOOLEAN|BOOL)(?:\\(1\\))?")) return "BOOLEAN";
        if ("BYTE".equals(type) || "TINYINT".equals(type)) return "SMALLINT";
        if ("TIMESTAMP".equals(type)) return "TIMESTAMP(6)";
        if ("TIMESTAMP WITH TIME ZONE".equals(type)) return "TIMESTAMP(6) WITH TIME ZONE";
        if ("TIME".equals(type)) return "TIME(0)";
        if ("TIME WITH TIME ZONE".equals(type)) return "TIME(0) WITH TIME ZONE";
        return type;
    }
}
