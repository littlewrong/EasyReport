package cn.easyreport.sync.mapper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KingbaseTypeMapperTest {

    private final KingbaseTypeMapper mapper = new KingbaseTypeMapper();

    @Test
    public void supportsEveryExistingSourceWithoutChangingOldMappings() {
        DatabaseTypeMapper[] sources = {new MySqlTypeMapper(), new TiDbTypeMapper(),
                new StarRocksTypeMapper(), new SqlServerTypeMapper(),
                new PostgreSqlTypeMapper(), new OracleTypeMapper()};
        String[][] examples = {{"decimal(18,2)", "NUMERIC(18,2)"},
                {"decimal(18,2)", "NUMERIC(18,2)"},
                {"decimal(18,2)", "NUMERIC(18,2)"},
                {"decimal(18,2)", "NUMERIC(18,2)"},
                {"numeric(18,2)", "numeric(18,2)"},
                {"number(18,2)", "NUMERIC(18,2)"}};
        for (int i = 0; i < sources.length; i++) {
            DatabaseTypeMapper source = sources[i];
            assertTrue(source.getSourceDatabaseType(), source.supportsTarget("kingbase"));
            assertTrue(source.getSupportedTargets().contains("KINGBASE"));
            assertEquals(source.getSourceDatabaseType(), examples[i][1], source.mapType("KINGBASE", examples[i][0]));
        }
        MySqlTypeMapper mysql = new MySqlTypeMapper();
        assertEquals("NUMBER(18,2)", mysql.mapType("ORACLE", "decimal(18,2)"));
        assertEquals("NUMERIC(18,2)", mysql.mapType("POSTGRESQL", "decimal(18,2)"));
        assertEquals("DECIMAL(18,2)", mysql.mapType("STARROCKS", "decimal(18,2)"));
    }

    @Test
    public void retainsUnsignedIntegerRangesWhenKingbaseIsTheTarget() {
        for (DatabaseTypeMapper source : new DatabaseTypeMapper[]{new MySqlTypeMapper(), new TiDbTypeMapper()}) {
            assertEquals("INTEGER", source.mapType("KINGBASE", "smallint(5) unsigned"));
            assertEquals("BIGINT", source.mapType("KINGBASE", "int unsigned zerofill"));
            assertEquals("NUMERIC(20,0)", source.mapType("KINGBASE", "bigint(20) unsigned"));
            assertEquals("NUMERIC(18,2)", source.mapType("KINGBASE", "decimal(18,2) zerofill"));
        }
        assertEquals("NUMERIC(39,0)", new StarRocksTypeMapper().mapType("KINGBASE", "largeint"));
    }

    @Test
    public void retainsTemporalPrecisionAndReportsDestinationLimitations() {
        OracleTypeMapper oracle = new OracleTypeMapper();
        assertEquals("TIMESTAMP(3)", oracle.mapType("KINGBASE", "TIMESTAMP(3)"));
        assertEquals("TIMESTAMP(6) WITH TIME ZONE",
                oracle.mapType("KINGBASE", "TIMESTAMP(9) WITH TIME ZONE"));
        assertTrue(oracle.isLossyConversion("KINGBASE", "TIMESTAMP(9) WITH TIME ZONE"));
        SqlServerTypeMapper sqlServer = new SqlServerTypeMapper();
        assertEquals("TIMESTAMP(4) WITH TIME ZONE", sqlServer.mapType("KINGBASE", "datetimeoffset(4)"));
        assertEquals("TIMESTAMP(6)", sqlServer.mapType("KINGBASE", "datetime2(7)"));
        assertTrue(sqlServer.isLossyConversion("KINGBASE", "datetime2(7)"));
        assertEquals("TIMESTAMP(6) WITH TIME ZONE", sqlServer.mapType("KINGBASE", "datetimeoffset"));
        assertTrue(sqlServer.isLossyConversion("KINGBASE", "datetimeoffset"));
        assertEquals("BYTEA", sqlServer.mapType("KINGBASE", "timestamp"));
    }

    @Test
    public void acceptsOracleModeNumericNamesAndNegativeScale() {
        for (String target : new String[]{"MYSQL", "TIDB", "STARROCKS"}) {
            assertEquals(target, "DECIMAL(18,2)", mapper.mapType(target, "NUMBER(18, 2)"));
            assertEquals(target, "DECIMAL(9,0)", mapper.mapType(target, "NUMBER(7,-2)"));
        }
        assertEquals("NUMERIC(18,2)", mapper.mapType("POSTGRESQL", "NUMBER(18,2)"));
        assertEquals("NUMBER(18,2)", mapper.mapType("ORACLE", "NUMERIC(18,2)"));
        assertEquals("NUMERIC(18,2)", mapper.mapType("SQLSERVER", "NUMBER(18,2)"));
        assertEquals("NUMERIC(9,0)", new OracleTypeMapper().mapType("KINGBASE", "NUMBER(7,-2)"));
        assertFalse(mapper.isLossyConversion("MYSQL", "NUMBER(18,2)"));
        assertTrue(mapper.isLossyConversion("MYSQL", "NUMERIC"));
    }

    @Test
    public void preservesLargeNumericValuesAsTextWhenDestinationPrecisionCannotFit() {
        assertEquals("LONGTEXT", mapper.mapType("MYSQL", "NUMBER(66,0)"));
        assertTrue(mapper.isLossyConversion("MYSQL", "NUMBER(66,0)"));
        assertEquals("LONGTEXT", mapper.mapType("TIDB", "NUMBER(40,31)"));
        assertEquals("NVARCHAR(MAX)", mapper.mapType("SQLSERVER", "NUMBER(39,0)"));
        assertEquals("STRING", mapper.mapType("STARROCKS", "NUMERIC(39,0)"));
        assertEquals("CLOB", mapper.mapType("ORACLE", "NUMERIC(39,2)"));
        assertTrue(mapper.isLossyConversion("ORACLE", "NUMERIC(39,2)"));
        assertEquals("DECIMAL(65,30)", mapper.mapType("MYSQL", "NUMERIC(65,30)"));
        assertFalse(mapper.isLossyConversion("MYSQL", "NUMERIC(65,30)"));
        assertEquals("NUMERIC(38,0)", mapper.mapType("SQLSERVER", "NUMERIC(38,0)"));
        assertEquals("NUMBER(38,-84)", mapper.mapType("ORACLE", "NUMBER(38,-84)"));
        assertFalse(mapper.isLossyConversion("ORACLE", "NUMBER(38,-84)"));
        assertEquals("LONGTEXT", mapper.mapType("MYSQL", "NUMBER(38,-84)"));
        assertTrue(mapper.isLossyConversion("MYSQL", "NUMBER(38,-84)"));
    }

    @Test
    public void expandsScaleGreaterThanPrecisionOnlyWhereDestinationRequiresIt() {
        assertEquals("DECIMAL(6,6)", mapper.mapType("MYSQL", "NUMBER(4,6)"));
        assertFalse(mapper.isLossyConversion("MYSQL", "NUMBER(4,6)"));
        assertEquals("NUMBER(4,6)", mapper.mapType("ORACLE", "NUMBER(4,6)"));
        assertEquals("NUMERIC(9,0)", mapper.mapType("POSTGRESQL", "NUMBER(7,-2)"));
        assertEquals("NUMERIC", mapper.mapType("POSTGRESQL", "NUMBER(1000,-2)"));
        assertEquals("NVARCHAR(MAX)", mapper.mapType("SQLSERVER", "NUMERIC(5,50)"));
        assertTrue(mapper.isLossyConversion("SQLSERVER", "NUMERIC(5,50)"));
    }

    @Test
    public void handlesAliasesAndLargeObjectsWithoutOracleNamesLeakingIntoOtherDdl() {
        assertEquals("VARCHAR(80)", mapper.mapType("MYSQL", "nvarchar2(80 char)"));
        assertEquals("NVARCHAR(80)", mapper.mapType("SQLSERVER", "VARCHAR2(80)"));
        assertEquals("LONGTEXT", mapper.mapType("MYSQL", "NCLOB"));
        assertEquals("BLOB", mapper.mapType("ORACLE", "BYTEA"));
        assertEquals("VARBINARY(MAX)", mapper.mapType("SQLSERVER", "RAW(2000)"));
        assertEquals("SMALLINT", mapper.mapType("MYSQL", "int2"));
        assertEquals("NUMBER(10)", mapper.mapType("ORACLE", "int4"));
        assertEquals("CHAR(40)", mapper.mapType("MYSQL", "bpchar(40)"));
        assertEquals("NCLOB", mapper.mapType("ORACLE", "VARCHAR2(5000)"));
    }

    @Test
    public void preservesFractionalSecondsAndTimeZonesWhenSupported() {
        assertEquals("DATETIME(6)", mapper.mapType("MYSQL", "TIMESTAMP(6) WITHOUT TIME ZONE"));
        assertEquals("TIMESTAMP(3) WITH TIME ZONE", mapper.mapType("ORACLE", "timestamptz(3)"));
        assertEquals("DATETIMEOFFSET(6)", mapper.mapType("SQLSERVER", "TIMESTAMP(6) WITH TIME ZONE"));
        assertEquals("TIMESTAMP WITH TIME ZONE", mapper.mapType("POSTGRESQL", "timestamptz"));
        assertTrue(mapper.isLossyConversion("MYSQL", "TIMESTAMP(6) WITH TIME ZONE"));
        assertFalse(mapper.isLossyConversion("SQLSERVER", "TIMESTAMP(6) WITH TIME ZONE"));
    }

    @Test
    public void keepsSelfMappingsAndNullOrUnknownDestinationsPredictable() {
        assertEquals("KINGBASE", mapper.getSourceDatabaseType());
        assertEquals("varchar2(80)", mapper.mapType("KINGBASE", "varchar2(80)"));
        assertEquals("NUMBER(18,2)", mapper.mapType("UNKNOWN", "NUMBER(18,2)"));
        assertEquals(null, mapper.mapType("MYSQL", null));
        assertFalse(mapper.supportsTarget(null));
        assertFalse(mapper.isLossyConversion(null, "NUMBER"));
    }
}
