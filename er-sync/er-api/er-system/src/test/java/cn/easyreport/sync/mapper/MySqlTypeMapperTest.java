package cn.easyreport.sync.mapper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class MySqlTypeMapperTest {

    private final MySqlTypeMapper mapper = new MySqlTypeMapper();

    @Test
    public void mapsUnsignedIntegersToTypesThatRetainTheirFullRange() {
        String[][] mappings = {
                {"tinyint(3)", "SMALLINT"},
                {"smallint(5)", "INT"},
                {"mediumint(8)", "INT"},
                {"int(1)", "BIGINT"},
                {"integer(11)", "BIGINT"},
                {"bigint(20)", "DECIMAL(20,0)"}
        };
        for (String[] mapping : mappings) {
            // ZEROFILL implies UNSIGNED in MySQL, even without an explicit UNSIGNED modifier.
            for (String suffix : new String[]{" unsigned", " unsigned zerofill", " zerofill"}) {
                String sourceType = mapping[0] + suffix;
                assertEquals(sourceType, mapping[1], mapper.mapType("STARROCKS", sourceType));
                assertFalse(sourceType, mapper.isLossyConversion("STARROCKS", sourceType));
            }
        }
    }

    @Test
    public void retainsSignedIntegerRangesAndDiscardsDisplayWidths() {
        String[][] mappings = {
                {"tinyint(1)", "TINYINT"},
                {"smallint(5)", "SMALLINT"},
                {"mediumint(8)", "INT"},
                {"int(1)", "INT"},
                {"integer(11)", "INT"},
                {"bigint(20)", "BIGINT"}
        };
        for (String[] mapping : mappings) {
            assertEquals(mapping[0], mapping[1], mapper.mapType("STARROCKS", mapping[0]));
            assertEquals(mapping[0] + " signed", mapping[1],
                    mapper.mapType("STARROCKS", mapping[0] + " signed"));
        }
    }

    @Test
    public void handlesIntegerModifiersWithoutDisplayWidthsAndWithMixedCaseWhitespace() {
        assertEquals("BIGINT", mapper.mapType("starrocks", "int unsigned zerofill"));
        assertEquals("BIGINT", mapper.mapType("STARROCKS", "  InT ( 1 ) UnSiGnEd\tZeRoFiLl  "));
        assertEquals("DECIMAL(20,0)", mapper.mapType("STARROCKS", "BIGINT ZEROFILL"));
    }

    @Test
    public void removesNumericModifiersWithoutLosingDecimalPrecision() {
        String[][] mappings = {
                {"float(10,2)", "FLOAT"},
                {"double(18,4)", "DOUBLE"},
                {"real", "DOUBLE"},
                {"decimal(18,2)", "DECIMAL(18,2)"},
                {"numeric(20,0)", "DECIMAL(20,0)"},
                {"decimal", "DECIMAL(10,0)"}
        };
        for (String[] mapping : mappings) {
            for (String suffix : new String[]{" unsigned zerofill", " zerofill"}) {
                String sourceType = mapping[0] + suffix;
                assertEquals(sourceType, mapping[1], mapper.mapType("STARROCKS", sourceType));
            }
        }
    }

    @Test
    public void retainsModifiersForMySqlCompatibleTargets() {
        for (String target : new String[]{"MYSQL", "TIDB"}) {
            for (String sourceType : new String[]{"int(1) unsigned zerofill", "bigint(20) unsigned",
                    "decimal(18,2) unsigned zerofill"}) {
                assertEquals(target + ": " + sourceType, sourceType, mapper.mapType(target, sourceType));
            }
        }
    }
}
