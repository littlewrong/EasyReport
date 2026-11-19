package cn.easyreport.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import cn.easyreport.definition.ReportDefinition;
import cn.easyreport.exception.ReportParseException;
import cn.easyreport.export.ReportRender;

public class ReportParserSecurityTest {
    private static final String SAFE_ERROR = "Invalid or unsafe report content.";
    private final ReportParser parser = new ReportParser();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsChineseCdataAndLegacyNamespaceWithoutFetchingSchema() {
        String xml = "<easyreport xmlns='http://www.example.org/easyreport' "
                + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' "
                + "xsi:schemaLocation='http://www.example.org/easyreport file:/not-a-real-report-schema.xsd'>"
                + axes(1, 1) + cell("A1", 1, 1, "", "<![CDATA[月度报表 <script>文字</script> ${正常表达式}]]>")
                + "</easyreport>";
        ReportDefinition report = parser.parseContent(xml, "file:中文.easyreport.xml");
        assertEquals("file:中文.easyreport.xml", report.getReportFullName());
        assertEquals("月度报表 <script>文字</script> ${正常表达式}", report.getCells().get(0).getValue().getValue());
    }

    @Test
    public void acceptsExistingReportFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/cn/easyreport/parser/valid-report.easyreport.xml")) {
            assertNotNull(input);
            ReportDefinition report = parser.parse(input, "fixture.xml");
            assertEquals(3, report.getRows().size());
            assertEquals(4, report.getColumns().size());
            assertFalse(report.getCells().isEmpty());
        }
    }

    @Test
    public void acceptsUtf16XmlStream() {
        String xml = "<?xml version='1.0' encoding='UTF-16'?>" + report("中文内容");
        ReportDefinition report = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_16)), "test.xml");
        assertEquals("中文内容", report.getCells().get(0).getValue().getValue());
    }

    @Test
    public void rejectsLocalExternalGeneralEntityWithoutDisclosingSentinelOrPath() throws Exception {
        Path sentinel = temporaryFolder.newFile("private-sentinel.txt").toPath();
        Files.write(sentinel, "PRIVATE_REPORT_SENTINEL".getBytes(StandardCharsets.UTF_8));
        String xml = "<!DOCTYPE easyreport [<!ENTITY private SYSTEM '" + sentinel.toUri() + "'>]>" + report("&private;");
        expectInvalid(() -> parser.parseContent(xml, "test.xml"));
        assertEquals("PRIVATE_REPORT_SENTINEL", new String(Files.readAllBytes(sentinel), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsUtf16DoctypeAndExternalEntity() throws Exception {
        Path sentinel = temporaryFolder.newFile("utf16-sentinel.txt").toPath();
        Files.write(sentinel, "UTF16_PRIVATE_REPORT".getBytes(StandardCharsets.UTF_8));
        String xml = "<?xml version='1.0' encoding='UTF-16'?>"
                + "<!DOCTYPE easyreport [<!ENTITY private SYSTEM '" + sentinel.toUri() + "'>]>" + report("&private;");
        expectInvalid(() -> parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_16)), "test.xml"));
    }

    @Test
    public void rejectsDoctypesIncludingExternalParameterEntitiesAndExternalDtd() throws Exception {
        Path dtd = temporaryFolder.newFile("local.dtd").toPath();
        Files.write(dtd, "<!ENTITY local 'PRIVATE_DTD_CONTENT'>".getBytes(StandardCharsets.UTF_8));
        String[] doctypes = {
                "<!DOCTYPE easyreport>",
                "<!DOCTYPE easyreport [<!ENTITY local 'internal'>]>",
                "<!DOCTYPE easyreport SYSTEM '" + dtd.toUri() + "'>",
                "<!DOCTYPE easyreport [<!ENTITY % remote SYSTEM '" + dtd.toUri() + "'>%remote;]>"
        };
        for (String doctype : doctypes) {
            expectInvalid(() -> parser.parseContent(doctype + "<easyreport/>", "test.xml"));
        }
    }

    @Test
    public void rejectsNullEmptyMalformedAndNonReportContentWithSafeErrors() {
        String[] invalid = {null, "", " \r\n\t", "<easyreport>", "<not-a-report/>", "<% application %>"};
        for (String xml : invalid) {
            expectInvalid(() -> parser.parseContent(xml, "private/server/path.xml"));
        }
        expectInvalid(() -> parser.parse(null, "private/server/path.xml"));
        expectInvalid(() -> parser.parse(new ByteArrayInputStream(new byte[0]), "test.xml"));
        expectInvalid(() -> parser.parse(new ByteArrayInputStream("  ".getBytes(StandardCharsets.UTF_8)), "test.xml"));
    }

    @Test
    public void limitsUtf8BytesRatherThanOnlyCharacterCount() {
        char[] chars = new char[ReportParser.MAX_REPORT_BYTES / 3 + 1];
        Arrays.fill(chars, '中');
        String oversized = new String(chars);
        assertTrue(oversized.length() < ReportParser.MAX_REPORT_BYTES);
        expectInvalid(() -> ReportParser.validateContent(oversized));
        expectInvalid(() -> parser.parseContent(oversized, "test.xml"));
    }

    @Test
    public void acceptsExactByteLimitButRejectsOneAdditionalByteFromStreamsAndStrings() {
        String prefix = "<easyreport><!--";
        String suffix = "--></easyreport>";
        char[] padding = new char[ReportParser.MAX_REPORT_BYTES - prefix.length() - suffix.length()];
        Arrays.fill(padding, 'a');
        String xml = prefix + new String(padding) + suffix;
        assertEquals(ReportParser.MAX_REPORT_BYTES, ReportParser.validateContent(xml).length);
        assertNotNull(parser.parseContent(xml, "test.xml"));
        expectInvalid(() -> ReportParser.validateContent(xml + " "));
        expectInvalid(() -> parser.parse(new ByteArrayInputStream((xml + " ").getBytes(StandardCharsets.UTF_8)), "test.xml"));
    }

    @Test
    public void rejectsTooDeepOrTooManyXmlElementsBeforeBuildingDom() {
        StringBuilder deep = new StringBuilder("<easyreport>");
        for (int i = 0; i < ReportParser.MAX_XML_DEPTH; i++) deep.append("<nested>");
        for (int i = 0; i < ReportParser.MAX_XML_DEPTH; i++) deep.append("</nested>");
        deep.append("</easyreport>");
        expectInvalid(() -> parser.parseContent(deep.toString(), "test.xml"));

        StringBuilder many = new StringBuilder("<easyreport>");
        for (int i = 0; i < ReportParser.MAX_XML_ELEMENTS; i++) many.append("<row row-number='1'/>");
        many.append("</easyreport>");
        expectInvalid(() -> parser.parseContent(many.toString(), "test.xml"));
    }

    @Test
    public void rejectsHugeSpansInvalidCoordinatesAndDuplicateCellsBeforeRebuild() {
        String[] invalid = {
                "<easyreport>" + axes(1, 1) + cell("A1", 1, 1, "row-span='2147483647'", "x") + "</easyreport>",
                "<easyreport>" + axes(1, 1) + cell("A1", 1, 1, "col-span='2147483647'", "x") + "</easyreport>",
                "<easyreport>" + axes(1, 1) + cell("A1", 1, 1, "row-span='-1'", "x") + "</easyreport>",
                "<easyreport><row row-number='0'/></easyreport>",
                "<easyreport><column col-number='2147483647'/></easyreport>",
                "<easyreport>" + axes(1, 1) + cell("A1", 0, 1, "", "x") + "</easyreport>",
                "<easyreport>" + axes(1, 1) + cell("A1", 2, 1, "", "x") + "</easyreport>",
                "<easyreport>" + axes(1, 1) + cell("A1", 1, 1, "", "x") + cell("B1", 1, 1, "", "y") + "</easyreport>"
        };
        for (String xml : invalid) expectInvalid(() -> parser.parseContent(xml, "test.xml"));
    }

    @Test
    public void rejectsAggregateSpanWorkEvenWhenEveryIndividualSpanIsWithinBounds() {
        StringBuilder xml = new StringBuilder("<easyreport>").append(axes(11, 1));
        for (int i = 1; i <= 11; i++) {
            xml.append(cell("A" + i, i, 1, "row-span='99990'", "x"));
        }
        xml.append("</easyreport>");
        expectInvalid(() -> parser.parseContent(xml.toString(), "test.xml"));
    }

    @Test
    public void rejectsLeftAndTopParentCyclesAndOverlongParentChains() {
        for (String attribute : new String[]{"left-cell", "top-cell"}) {
            String self = "<easyreport>" + axes(1, 1) + cell("A1", 1, 1, attribute + "='A1'", "x") + "</easyreport>";
            expectInvalid(() -> parser.parseContent(self, "test.xml"));
            String cycle = "<easyreport>" + axes(2, 1)
                    + cell("A1", 1, 1, attribute + "='A2'", "x")
                    + cell("A2", 2, 1, attribute + "='A1'", "x") + "</easyreport>";
            expectInvalid(() -> parser.parseContent(cycle, "test.xml"));
        }
        StringBuilder chain = new StringBuilder("<easyreport>").append(axes(ReportParser.MAX_PARENT_DEPTH + 1, 1));
        for (int i = 1; i <= ReportParser.MAX_PARENT_DEPTH + 1; i++) {
            chain.append(cell("A" + i, i, 1, "left-cell='root'", "x"));
        }
        chain.append("</easyreport>");
        expectInvalid(() -> parser.parseContent(chain.toString(), "test.xml"));
    }

    @Test
    public void acceptsNormalThreeHundredRowReportAndRebuildsMaximumParentDepth() {
        for (int rows : new int[]{300, ReportParser.MAX_PARENT_DEPTH}) {
            StringBuilder xml = new StringBuilder("<easyreport>").append(axes(rows, 1));
            for (int i = 1; i <= rows; i++) xml.append(cell("A" + i, i, 1, "left-cell='root'", "x"));
            xml.append("</easyreport>");
            ReportDefinition report = parser.parseContent(xml.toString(), "test.xml");
            new ReportRender().rebuildReportDefinition(report);
            assertEquals(rows, report.getCells().size());
            assertEquals(rows - 1, report.getCells().get(0).getColumnChildrenCells().size());
        }
    }

    @Test
    public void rejectsExcessiveAggregateAncestorWork() {
        int rows = 800;
        StringBuilder xml = new StringBuilder("<easyreport>").append(axes(rows, 4));
        for (int column = 1; column <= 4; column++) {
            for (int row = 1; row <= rows; row++) {
                xml.append(cell("C" + column + "R" + row, row, column, "left-cell='root'", "x"));
            }
        }
        xml.append("</easyreport>");
        expectInvalid(() -> parser.parseContent(xml.toString(), "test.xml"));
    }

    private static String report(String value) {
        return "<easyreport>" + axes(1, 1) + cell("A1", 1, 1, "", value) + "</easyreport>";
    }

    private static String axes(int rows, int columns) {
        StringBuilder xml = new StringBuilder();
        for (int i = 1; i <= rows; i++) xml.append("<row row-number='").append(i).append("' height='20'/>");
        for (int i = 1; i <= columns; i++) xml.append("<column col-number='").append(i).append("' width='60'/>");
        return xml.toString();
    }

    private static String cell(String name, int row, int column, String attributes, String value) {
        return "<cell name='" + name + "' row='" + row + "' col='" + column + "' " + attributes
                + "><simple-value>" + value + "</simple-value></cell>";
    }

    private void expectInvalid(Runnable action) {
        try {
            action.run();
            fail("Unsafe report must be rejected.");
        } catch (ReportParseException exception) {
            assertEquals(SAFE_ERROR, exception.getMessage());
            assertNull("Parser errors must not expose their underlying causes.", exception.getCause());
        }
    }
}
