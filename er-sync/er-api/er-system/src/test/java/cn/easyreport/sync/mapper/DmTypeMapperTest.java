package cn.easyreport.sync.mapper;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DmTypeMapperTest {
    private final DmTypeMapper dm = new DmTypeMapper();

    @Test
    public void everyExistingSourceCanMapToDmWithoutChangingOldDestinations() {
        DatabaseTypeMapper[] sources = {new MySqlTypeMapper(), new TiDbTypeMapper(), new StarRocksTypeMapper(),
            new SqlServerTypeMapper(), new PostgreSqlTypeMapper(), new OracleTypeMapper(), new KingbaseTypeMapper()};
        for (DatabaseTypeMapper source : sources) {
            assertTrue(source.getSourceDatabaseType(), source.supportsTarget("dm"));
            assertTrue(source.getSupportedTargets().contains("DM"));
            assertEquals("NUMERIC(18,2)", source.mapType("DM", "NUMERIC(18,2)"));
        }
        MySqlTypeMapper mysql = new MySqlTypeMapper();
        assertEquals("NUMBER(18,2)", mysql.mapType("ORACLE", "DECIMAL(18,2)"));
        assertEquals("NUMERIC(18,2)", mysql.mapType("POSTGRESQL", "DECIMAL(18,2)"));
        assertEquals("NUMERIC(18,2)", mysql.mapType("KINGBASE", "DECIMAL(18,2)"));
    }

    @Test
    public void dmSourceSupportsAllSevenDestinationsAndKeepsSelfMappings() {
        assertEquals("DM", dm.getSourceDatabaseType());
        for (String target : Arrays.asList("MYSQL", "TIDB", "STARROCKS", "SQLSERVER", "POSTGRESQL", "ORACLE", "KINGBASE")) {
            assertTrue(dm.supportsTarget(target));
            assertTrue(dm.getSupportedTargets().contains(target));
        }
        assertEquals("DECIMAL(18,2)", dm.mapType("MYSQL", "NUMBER(18,2)"));
        assertEquals("NUMERIC(18,2)", dm.mapType("KINGBASE", "NUMBER(18,2)"));
        assertEquals("NUMBER(18,2)", dm.mapType("ORACLE", "DECIMAL(18,2)"));
        assertEquals("varchar(40 char)", dm.mapType("DM", "varchar(40 char)"));
        assertEquals("unknown", dm.mapType("UNSUPPORTED", "unknown"));
        assertNull(dm.mapType("MYSQL", null));
        assertFalse(dm.supportsTarget(null));
    }

    @Test
    public void unsignedIntegersAndBit64DoNotOverflowDmSignedTypes() {
        for (DatabaseTypeMapper source : new DatabaseTypeMapper[]{new MySqlTypeMapper(), new TiDbTypeMapper()}) {
            assertEquals("TINYINT", source.mapType("DM", "tinyint(4)"));
            assertEquals("INTEGER", source.mapType("DM", "int(11)"));
            assertEquals("SMALLINT", source.mapType("DM", "tinyint(3) unsigned"));
            assertEquals("INTEGER", source.mapType("DM", "smallint(5) unsigned"));
            assertEquals("BIGINT", source.mapType("DM", "int(10) unsigned zerofill"));
            assertEquals("NUMERIC(20,0)", source.mapType("DM", "bigint(20) unsigned"));
            assertEquals("BIT", source.mapType("DM", "bit(1)"));
            assertEquals("NUMERIC(20,0)", source.mapType("DM", "bit(64)"));
        }
        assertEquals("CLOB", new StarRocksTypeMapper().mapType("DM", "LARGEINT"));
        assertTrue(new StarRocksTypeMapper().isLossyConversion("DM", "LARGEINT"));
    }

    @Test
    public void unicodeLengthsUseCharacterSemanticsAndLargeValuesUseLobs() {
        DatabaseTypeMapper pg = new PostgreSqlTypeMapper();
        assertEquals("VARCHAR(255 CHAR)", pg.mapType("DM", "varchar(255)"));
        assertEquals("CHAR(36 CHAR)", pg.mapType("DM", "char(36)"));
        assertEquals("VARCHAR(400 CHAR)", pg.mapType("DM", "varchar(400)"));
        assertEquals("CLOB", pg.mapType("DM", "varchar(401)"));
        assertEquals("CLOB", new OracleTypeMapper().mapType("DM", "NVARCHAR2(4000)"));
        assertEquals("VARCHAR(80 CHAR)", new OracleTypeMapper().mapType("DM", "VARCHAR2(80 BYTE)"));
        assertEquals("CLOB", new SqlServerTypeMapper().mapType("DM", "NVARCHAR(MAX)"));
        assertEquals("LONGTEXT", dm.mapType("MYSQL", "CLOB"));
        assertEquals("VARCHAR(80)", dm.mapType("KINGBASE", "VARCHAR2(80 CHAR)"));
    }

    @Test
    public void decimalsRespectDestinationPrecisionAndNeverSilentlyDropScale() {
        DatabaseTypeMapper pg = new PostgreSqlTypeMapper();
        assertEquals("NUMERIC(38,18)", pg.mapType("DM", "numeric(38,18)"));
        assertEquals("CLOB", pg.mapType("DM", "numeric(39,18)"));
        assertTrue(pg.isLossyConversion("DM", "numeric(39,18)"));
        assertEquals("CLOB", pg.mapType("DM", "numeric"));
        assertTrue(pg.isLossyConversion("DM", "numeric"));
        assertEquals("NUMERIC(9,0)", new OracleTypeMapper().mapType("DM", "NUMBER(7,-2)"));
        assertEquals("NUMERIC", new OracleTypeMapper().mapType("DM", "NUMBER"));
        assertFalse(new OracleTypeMapper().isLossyConversion("DM", "NUMBER"));
        assertEquals("NUMERIC(4,4)", pg.mapType("DM", "NUMERIC(2,4)"));
        assertFalse(pg.isLossyConversion("DM", "NUMERIC(2,4)"));
        assertEquals("NUMERIC(10,0)", new MySqlTypeMapper().mapType("DM", "DECIMAL"));
        assertEquals("NUMBER", dm.mapType("ORACLE", "NUMERIC"));
        assertTrue(dm.isLossyConversion("ORACLE", "NUMERIC"));
        assertEquals("NUMERIC(18,2)", new StarRocksTypeMapper().mapType("DM", "DECIMAL128(18,2)"));
    }

    @Test
    public void dateAndTimestampSemanticsAreSpecificToTheSource() {
        assertEquals("TIMESTAMP(0)", new OracleTypeMapper().mapType("DM", "DATE"));
        assertEquals("DATE", new PostgreSqlTypeMapper().mapType("DM", "DATE"));
        assertEquals("DATE", dm.mapType("MYSQL", "DATE"));
        assertEquals("DATE", dm.mapType("ORACLE", "DATE"));
        assertEquals("DATETIME(6)", dm.mapType("MYSQL", "DATETIME(6)"));
        assertEquals("TIMESTAMP(6)", dm.mapType("KINGBASE", "DATETIME"));
        assertEquals("TIMESTAMP(9)", dm.mapType("ORACLE", "DATETIME2"));
        assertEquals("TIMESTAMP(6)", dm.mapType("POSTGRESQL", "DATETIME2"));
        assertTrue(dm.isLossyConversion("POSTGRESQL", "DATETIME2"));
    }

    @Test
    public void temporalPrecisionAndTimezoneLossAreReported() {
        OracleTypeMapper oracle = new OracleTypeMapper();
        assertEquals("TIMESTAMP(6) WITH TIME ZONE", oracle.mapType("DM", "TIMESTAMP(9) WITH TIME ZONE"));
        assertTrue(oracle.isLossyConversion("DM", "TIMESTAMP(9) WITH TIME ZONE"));
        PostgreSqlTypeMapper pg = new PostgreSqlTypeMapper();
        assertEquals("TIMESTAMP(3) WITH TIME ZONE", pg.mapType("DM", "TIMESTAMPTZ(3)"));
        assertFalse(pg.isLossyConversion("DM", "TIMESTAMPTZ(3)"));
        SqlServerTypeMapper sqlServer = new SqlServerTypeMapper();
        assertEquals("TIMESTAMP(6)", sqlServer.mapType("DM", "DATETIME2"));
        assertTrue(sqlServer.isLossyConversion("DM", "DATETIME2"));
        assertEquals("TIMESTAMP(6) WITH TIME ZONE", sqlServer.mapType("DM", "DATETIMEOFFSET(7)"));
        assertEquals("VARCHAR(40 CHAR)", new MySqlTypeMapper().mapType("DM", "TIME(6)"));
        assertTrue(new MySqlTypeMapper().isLossyConversion("DM", "TIME(6)"));
    }

    @Test
    public void binaryAndNativeAliasesDoNotLeakUnsupportedTypesIntoDmDdl() {
        assertEquals("BINARY(8)", new SqlServerTypeMapper().mapType("DM", "TIMESTAMP"));
        assertEquals("BLOB", new PostgreSqlTypeMapper().mapType("DM", "BYTEA"));
        assertEquals("VARBINARY(80)", new OracleTypeMapper().mapType("DM", "RAW(80)"));
        assertEquals("BLOB", new SqlServerTypeMapper().mapType("DM", "VARBINARY(MAX)"));
        assertEquals("BIT", new PostgreSqlTypeMapper().mapType("DM", "BOOLEAN"));
        assertEquals("VARCHAR(36 CHAR)", new PostgreSqlTypeMapper().mapType("DM", "UUID"));
        assertEquals("CLOB", new PostgreSqlTypeMapper().mapType("DM", "JSONB"));
        assertEquals("CLOB", new PostgreSqlTypeMapper().mapType("DM", "custom_domain"));
        assertTrue(new PostgreSqlTypeMapper().isLossyConversion("DM", "custom_domain"));
        assertEquals("LONGBLOB", dm.mapType("MYSQL", "IMAGE"));
        assertEquals("BYTEA", dm.mapType("KINGBASE", "BLOB"));
        assertEquals("NUMBER(1)", dm.mapType("ORACLE", "BIT"));
        assertEquals("BOOLEAN", dm.mapType("KINGBASE", "BIT"));
    }
}
