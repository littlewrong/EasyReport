package cn.easyreport.sync.datasync;

import java.sql.Types;
import java.math.BigInteger;
import org.junit.Test;

import static org.junit.Assert.*;

public class StarRocksJdbcResourceTargetTypesTest {
    @Test
    public void unsignedIntegerNarrowingNeedsAnActualRangeProof() {
        StarRocksJdbcResourceSource.Column source = column(Types.TINYINT, "TINYINT UNSIGNED", "java.lang.Integer", "INT", 3, 0);
        StarRocksJdbcResourceTargetTypes.IntegerRange range = StarRocksJdbcResourceTargetTypes.integerNarrowing(source, "TINYINT");
        assertEquals(BigInteger.valueOf(-128), range.minimum);
        assertEquals(BigInteger.valueOf(127), range.maximum);
        range.verify(BigInteger.ZERO, BigInteger.valueOf(127));
        rejectRange(range, BigInteger.ZERO, BigInteger.valueOf(128));
        rejectRange(range, BigInteger.valueOf(-129), BigInteger.ZERO);
        assertNull(StarRocksJdbcResourceTargetTypes.integerNarrowing(source, "SMALLINT"));
        rejects(source, "TINYINT"); // The original full-domain API stays strict.
    }

    @Test
    public void bitAndBigIntegerNarrowingUsesExactIntegerAndDecimalBounds() {
        StarRocksJdbcResourceTargetTypes.IntegerRange bigint =
                StarRocksJdbcResourceTargetTypes.integerNarrowing(numericBit(64), "BIGINT");
        bigint.verify(BigInteger.ZERO, BigInteger.valueOf(Long.MAX_VALUE));
        rejectRange(bigint, BigInteger.ZERO, BigInteger.ONE.shiftLeft(63));
        StarRocksJdbcResourceTargetTypes.IntegerRange decimal =
                StarRocksJdbcResourceTargetTypes.integerNarrowing(numericBit(8), "DECIMAL(3,1)");
        assertEquals(BigInteger.valueOf(99), decimal.maximum);
        decimal.verify(BigInteger.ZERO, BigInteger.valueOf(99));
        rejectRange(decimal, BigInteger.ZERO, BigInteger.valueOf(100));
        StarRocksJdbcResourceSource.Column numeric = column(Types.NUMERIC, "NUMERIC", "java.math.BigInteger", "LARGEINT", 38, 0);
        StarRocksJdbcResourceTargetTypes.IntegerRange tiny = StarRocksJdbcResourceTargetTypes.integerNarrowing(numeric, "TINYINT");
        tiny.verify(BigInteger.valueOf(-128), BigInteger.valueOf(127));
        rejectRange(tiny, null, BigInteger.ZERO);
        rejectRange(tiny, BigInteger.ONE, BigInteger.ZERO);
    }

    @Test
    public void integerRangeProbeNeverRelaxesFractionalFloatBooleanOrUnknownConversions() {
        StarRocksJdbcResourceSource.Column[] sources = {
                column(Types.DECIMAL, "DECIMAL(8,2)", "java.math.BigDecimal", "DECIMAL(8,2)", 8, 2),
                column(Types.NUMERIC, "NUMERIC(3,0)", "java.math.BigDecimal", "DECIMAL(3,0)", 3, 0),
                column(Types.DOUBLE, "DOUBLE", "java.lang.Double", "DOUBLE", 15, 0),
                numericBit(8), numericBit(8), numericBit(8)
        };
        String[] targets = {"TINYINT", "TINYINT", "INT", "BOOLEAN", "FLOAT", "ARRAY<INT>"};
        for (int index = 0; index < sources.length; index++) {
            try {
                StarRocksJdbcResourceTargetTypes.integerNarrowing(sources[index], targets[index]);
                fail("Expected unchanged non-integer conversion rejection");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("尚未清空目标表"));
            }
        }
    }

    private static void rejectRange(StarRocksJdbcResourceTargetTypes.IntegerRange range, BigInteger min, BigInteger max) {
        try { range.verify(min, max); fail("Expected actual integer range rejection"); }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(range.columnName));
            assertTrue(expected.getMessage(), expected.getMessage().contains("尚未清空目标表"));
        }
    }

    @Test
    public void byteTextTransportRetainsSignedTinyintDomain() {
        StarRocksJdbcResourceSource.Column source = column(Types.TINYINT, "TINYINT", "java.lang.Byte", "VARCHAR(4)", 3, 0);
        accepts(source, "TINYINT", "SMALLINT", "BIGINT", "DECIMAL(3,0)", "DECIMAL(5,2)", "VARCHAR(4)", "STRING");
        rejects(source, "BOOLEAN", "DECIMAL(2,0)", "DECIMAL(4,2)", "VARCHAR(3)", "FLOAT", "DOUBLE");
    }

    @Test
    public void unsignedIntegerRangeStaysDistinctFromSourceVerifiedTinyintBooleans() {
        StarRocksJdbcResourceSource.Column source = column(Types.SMALLINT, "TINYINT(1) UNSIGNED", "java.lang.Integer", "INT", 3, 0);
        accepts(source, "SMALLINT", "DECIMAL(3,0)", "VARCHAR(3)");
        rejects(source, "TINYINT", "BOOLEAN", "DECIMAL(2,0)");
        StarRocksJdbcResourceSource.Column folded = column(Types.BIT, "TINYINT(1) UNSIGNED", "java.lang.Boolean", "BOOLEAN", 1, 0);
        accepts(folded, "BOOLEAN", "TINYINT", "SMALLINT", "DECIMAL(1,0)");
        rejects(folded, "FLOAT", "DOUBLE");
    }

    @Test
    public void sqlServerTinyintKeepsItsUnsignedDomain() {
        StarRocksJdbcResourceSource.Column source = column(Types.TINYINT, "TINYINT UNSIGNED", "java.lang.Short", "SMALLINT", 3, 0);
        accepts(source, "SMALLINT", "INT", "DECIMAL(3,0)", "VARCHAR(3)");
        rejects(source, "TINYINT", "BOOLEAN", "DECIMAL(2,0)", "VARCHAR(2)");
    }

    @Test
    public void mediumIntegersAndZerofillUseNativeRangesInsteadOfJavaContainerWidth() {
        StarRocksJdbcResourceSource.Column medium = column(Types.INTEGER, "MEDIUMINT", "java.lang.Integer", "INT", 8, 0);
        accepts(medium, "INT", "DECIMAL(7,0)");
        rejects(medium, "SMALLINT", "DECIMAL(6,0)");
        StarRocksJdbcResourceSource.Column zerofill = column(Types.BIGINT, "INT ZEROFILL", "java.lang.Long", "BIGINT", 10, 0);
        accepts(zerofill, "BIGINT", "DECIMAL(10,0)");
        rejects(zerofill, "INT", "DECIMAL(9,0)");
    }

    @Test
    public void unsignedBigintFitsLargeintOrTwentyDigitDecimalButNotBigint() {
        StarRocksJdbcResourceSource.Column source = column(Types.BIGINT, "BIGINT UNSIGNED", "java.math.BigInteger", "LARGEINT", 20, 0);
        accepts(source, "LARGEINT", "DECIMAL(20,0)", "VARCHAR(20)");
        rejects(source, "BIGINT", "DECIMAL(19,0)", "DECIMAL(21,2)", "VARCHAR(19)", "DOUBLE");
    }

    @Test
    public void bigIntegerNumericUsesItsDeclaredDecimalPrecision() {
        StarRocksJdbcResourceSource.Column eighteen = column(Types.NUMERIC, "NUMERIC", "java.math.BigInteger", "LARGEINT", 18, 0);
        accepts(eighteen, "BIGINT", "DECIMAL(18,0)", "VARCHAR(19)");
        rejects(eighteen, "INT", "DECIMAL(17,0)", "VARCHAR(18)");
        StarRocksJdbcResourceSource.Column nineteen = column(Types.NUMERIC, "NUMERIC", "java.math.BigInteger", "LARGEINT", 19, 0);
        accepts(nineteen, "LARGEINT", "DECIMAL(19,0)");
        rejects(nineteen, "BIGINT");
        StarRocksJdbcResourceSource.Column thirtyEight = column(Types.NUMERIC, "NUMERIC", "java.math.BigInteger", "LARGEINT", 38, 0);
        accepts(thirtyEight, "LARGEINT", "DECIMAL(38,0)");
    }

    @Test
    public void largeintStringTransportRequiresTheFullSigned128BitDomain() {
        StarRocksJdbcResourceSource.Column source = new StarRocksJdbcResourceSource.Column("amount", "amount", "orders", "business",
                Types.VARCHAR, "LARGEINT", "java.lang.String", "VARCHAR(40)", true, 38, 0, -1, false, true, false, false);
        accepts(source, "LARGEINT", "VARCHAR(40)", "STRING");
        rejects(source, "BIGINT", "DECIMAL(38,0)", "VARCHAR(39)", "DOUBLE");
    }

    @Test
    public void booleansCanWidenToSafeIntegersAndDecimals() {
        StarRocksJdbcResourceSource.Column source = column(Types.BOOLEAN, "BOOLEAN", "java.lang.Boolean", "BOOLEAN", 1, 0);
        accepts(source, "BOOLEAN", "BOOL", "TINYINT", "BIGINT", "DECIMAL(1,0)", "VARCHAR(5)");
        rejects(source, "DATE", "FLOAT", "DOUBLE", "VARCHAR(4)");
        StarRocksJdbcResourceSource.Column multiBit = column(Types.BIT, "BIT(8)", "java.lang.Boolean", "BOOLEAN", 8, 0);
        rejects(multiBit, "BOOLEAN", "INT", "STRING");
    }

    @Test
    public void compatibleBit8IsAnUnsignedNumberAndNeverBoolean() {
        StarRocksJdbcResourceSource.Column source = numericBit(8);
        accepts(source, "SMALLINT", "INT", "BIGINT", "LARGEINT", "DECIMAL(3,0)", "VARCHAR(3)");
        rejects(source, "BOOLEAN", "TINYINT", "DECIMAL(2,0)", "VARCHAR(2)", "FLOAT", "DOUBLE");
    }

    @Test
    public void compatibleBitWidthsCannotOverflowExistingIntegerTargets() {
        accepts(numericBit(31), "INT", "BIGINT");
        rejects(numericBit(32), "INT");
        accepts(numericBit(32), "BIGINT");
        accepts(numericBit(63), "BIGINT");
        StarRocksJdbcResourceSource.Column bit64 = numericBit(64);
        accepts(bit64, "LARGEINT", "DECIMAL(20,0)", "VARCHAR(20)");
        rejects(bit64, "BIGINT", "DECIMAL(19,0)", "VARCHAR(19)", "BOOLEAN");
    }

    private static StarRocksJdbcResourceSource.Column numericBit(int width) {
        return new StarRocksJdbcResourceSource.Column("col_bit", "col_bit", "orders", "business",
                Types.BIT, "BIT(" + width + ")", "java.lang.Boolean", "LARGEINT", true,
                width, 0, -1, false, false, false, false, width);
    }

    @Test
    public void integerDecimalDestinationsNeedEnoughWholeNumberDigits() {
        StarRocksJdbcResourceSource.Column source = column(Types.BIGINT, "BIGINT", "java.lang.Long", "BIGINT", 19, 0);
        accepts(source, "BIGINT", "LARGEINT", "DECIMAL(19,0)", "DECIMAL(21,2)", "VARCHAR(20)");
        rejects(source, "INT", "DECIMAL(18,0)", "DECIMAL(20,2)", "VARCHAR(19)", "FLOAT", "DOUBLE");
    }

    @Test
    public void decimalsMustKeepBothWholeAndFractionalDigits() {
        StarRocksJdbcResourceSource.Column source = column(Types.DECIMAL, "DECIMAL(20,8)", "java.math.BigDecimal", "DECIMAL(20,8)", 20, 8);
        accepts(source, "DECIMAL(20,8)", "DECIMAL128(22,10)", "VARCHAR(22)", "STRING");
        rejects(source, "DECIMAL(20,9)", "DECIMAL(19,7)", "BIGINT", "DOUBLE", "VARCHAR(21)");
        StarRocksJdbcResourceSource.Column allFraction = column(Types.NUMERIC, "NUMERIC(3,3)", "java.math.BigDecimal", "DECIMAL(3,3)", 3, 3);
        accepts(allFraction, "DECIMAL(4,3)", "VARCHAR(6)");
        rejects(allFraction, "VARCHAR(5)");
    }

    @Test
    public void integralDecimalsCanUseAnIntegerOnlyWhenItsEntireRangeFits() {
        StarRocksJdbcResourceSource.Column source = column(Types.NUMERIC, "NUMERIC(3,0)", "java.math.BigDecimal", "DECIMAL(3,0)", 3, 0);
        accepts(source, "SMALLINT", "INT", "DECIMAL(3,0)");
        rejects(source, "TINYINT");
    }

    @Test
    public void floatingPointCanWidenButNeverNarrowOrBecomeAnExactNumeric() {
        StarRocksJdbcResourceSource.Column single = column(Types.REAL, "FLOAT", "java.lang.Float", "FLOAT", 7, 0);
        accepts(single, "FLOAT", "DOUBLE", "VARCHAR(160)", "STRING");
        rejects(single, "BIGINT", "DECIMAL(38,10)", "VARCHAR(159)");
        StarRocksJdbcResourceSource.Column doublePrecision = column(Types.DOUBLE, "DOUBLE", "java.lang.Double", "DOUBLE", 15, 0);
        accepts(doublePrecision, "DOUBLE", "DOUBLE PRECISION", "VARCHAR(1100)", "STRING");
        rejects(doublePrecision, "FLOAT", "DECIMAL(38,10)", "BIGINT", "VARCHAR(1099)");
    }

    @Test
    public void datesCanWidenToDatetimeButTimestampsCannotLoseClockOrFractionalPrecision() {
        StarRocksJdbcResourceSource.Column date = column(Types.DATE, "DATE", "java.sql.Date", "DATE", 10, 0);
        accepts(date, "DATE", "DATETIME", "DATETIME(0)", "VARCHAR(10)");
        rejects(date, "INT", "VARCHAR(9)");
        StarRocksJdbcResourceSource.Column timestamp = column(Types.TIMESTAMP, "DATE", "java.sql.Timestamp", "DATETIME", 26, 6);
        accepts(timestamp, "DATETIME", "DATETIME(6)", "VARCHAR(26)", "STRING");
        rejects(timestamp, "DATE", "DATETIME(0)", "DATETIME(3)", "VARCHAR(25)");
        StarRocksJdbcResourceSource.Column milliseconds = column(Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", "DATETIME", 23, 3);
        accepts(milliseconds, "DATETIME(3)", "DATETIME(6)", "DATETIME");
        rejects(milliseconds, "DATETIME(2)");
    }

    @Test
    public void oracleDateExposedAsTimestampRetainsItsClock() {
        StarRocksJdbcResourceSource.Column source = column(Types.DATE, "DATE", "java.sql.Timestamp", "DATETIME", 19, 0);
        accepts(source, "DATETIME", "DATETIME(0)", "VARCHAR(19)");
        rejects(source, "DATE", "VARCHAR(10)");
    }

    @Test
    public void unknownMalformedOrUnsupportedTargetTypesAreRejected() {
        StarRocksJdbcResourceSource.Column source = column(Types.INTEGER, "INTEGER", "java.lang.Integer", "INT", 10, 0);
        rejects(source, null, "", "BINARY(16)", "ARRAY<INT>", "INT UNSIGNED", "DECIMAL(39,0)",
                "DECIMAL(5,6)", "DECIMAL", "DATETIME(7)", "VARCHAR(0)", "VARCHAR(999999999999999999999999)");
        accepts(source, " int(11) ", " decimal(12, 2) ");
    }

    @Test
    public void invalidSourcePrecisionCannotBeProvedSafeEvenForAStringTarget() {
        StarRocksJdbcResourceSource.Column source = column(Types.NUMERIC, "NUMERIC", "java.math.BigInteger", "LARGEINT", 0, 0);
        rejects(source, "LARGEINT", "DECIMAL(38,0)", "STRING");
        StarRocksJdbcResourceSource.Column fractional = column(Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", "DATETIME", 29, 9);
        rejects(fractional, "DATETIME", "STRING");
    }

    @Test
    public void errorsIdentifyTheFieldAndTypesWithoutEchoingArbitraryDriverText() {
        StarRocksJdbcResourceSource.Column source = column(Types.BIGINT, "BIGINT", "java.lang.Long", "BIGINT", 19, 0);
        try {
            StarRocksJdbcResourceTargetTypes.verify(source, "jdbc:mysql://user:secret@host/database");
            fail("Expected unknown destination metadata to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("amount"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("BIGINT"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("尚未清空目标表"));
            assertFalse(expected.getMessage(), expected.getMessage().contains("secret"));
            assertFalse(expected.getMessage(), expected.getMessage().contains("jdbc:mysql"));
        }
    }

    private static StarRocksJdbcResourceSource.Column column(int jdbcType, String nativeType, String javaClass,
            String externalType, int precision, int scale) {
        return new StarRocksJdbcResourceSource.Column("amount", "amount", "orders", "business", jdbcType,
                nativeType, javaClass, externalType, true, precision, scale, -1, false, false, false, false);
    }

    private static void accepts(StarRocksJdbcResourceSource.Column source, String... targets) {
        for (String target : targets) StarRocksJdbcResourceTargetTypes.verify(source, target);
    }

    private static void rejects(StarRocksJdbcResourceSource.Column source, String... targets) {
        for (String target : targets) {
            try {
                StarRocksJdbcResourceTargetTypes.verify(source, target);
                fail("Expected " + source.nativeType + " -> " + target + " to be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(source.name));
                assertTrue(expected.getMessage(), expected.getMessage().contains("尚未清空目标表"));
            }
        }
    }
}
