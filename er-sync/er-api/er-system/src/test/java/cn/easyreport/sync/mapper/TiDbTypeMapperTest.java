package cn.easyreport.sync.mapper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TiDbTypeMapperTest {

    private final TiDbTypeMapper mapper = new TiDbTypeMapper();

    @Test
    public void unsignedIntegersGetTargetsThatPreserveTheirFullRange() {
        String[][] mappings = {{"tinyint", "SMALLINT"}, {"smallint", "INT"}, {"mediumint", "INT"},
                {"int", "BIGINT"}, {"integer", "BIGINT"}, {"bigint", "DECIMAL(20,0)"}};
        for (String[] mapping : mappings) {
            for (String modifiers : new String[]{" unsigned", "(8) unsigned", "(8) unsigned zerofill", " zerofill"}) {
                assertEquals(mapping[0] + modifiers, mapping[1], mapper.mapType("STARROCKS", mapping[0] + modifiers));
            }
        }
        assertEquals("TINYINT", mapper.mapType("STARROCKS", "TINYINT"));
        assertEquals("tinyint(8) unsigned", mapper.mapType("MYSQL", "tinyint(8) unsigned"));
    }

    @Test
    public void mapsEveryBitWidthToAStarRocksTypeWithEnoughUnsignedRange() {
        for (int width = 1; width <= 64; width++) {
            String sourceType = "bit(" + width + ")";
            String expected = width == 1 ? "BOOLEAN" : width <= 31 ? "INT" : width <= 63 ? "BIGINT" : "LARGEINT";
            assertEquals(sourceType, expected, mapper.mapType("STARROCKS", sourceType));
            assertFalse(sourceType, mapper.isLossyConversion("STARROCKS", sourceType));
        }
        assertEquals("INT", mapper.mapType("STARROCKS", "BIT(8)")); // Preserve 0..255.
        assertEquals("BOOLEAN", mapper.mapType("STARROCKS", "BIT"));
        assertEquals("LARGEINT", mapper.mapType("starrocks", "  bIt ( 64 )  "));
        assertEquals("BIT(0)", mapper.mapType("STARROCKS", "BIT(0)"));
        assertEquals("BIT(65)", mapper.mapType("STARROCKS", "BIT(65)"));
    }

    @Test
    public void retainsExistingBitMappingsForOtherTargets() {
        for (String target : new String[]{"MYSQL", "TIDB"}) {
            assertEquals(target, "bit(64)", mapper.mapType(target, "bit(64)"));
        }
        assertEquals("BIT", mapper.mapType("SQLSERVER", "BIT(1)"));
        assertEquals("VARBINARY(8)", mapper.mapType("SQLSERVER", "BIT(64)"));
        assertEquals("BOOLEAN", mapper.mapType("POSTGRESQL", "BIT(1)"));
        assertEquals("BIT(64)", mapper.mapType("POSTGRESQL", "BIT(64)"));
        assertEquals("NUMBER(10)", mapper.mapType("ORACLE", "BIT(64)"));
    }
}
