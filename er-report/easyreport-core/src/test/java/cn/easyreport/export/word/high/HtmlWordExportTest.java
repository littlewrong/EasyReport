package cn.easyreport.export.word.high;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STHeightRule;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;

import cn.easyreport.build.paging.Page;
import cn.easyreport.definition.value.SimpleValue;
import cn.easyreport.export.ContractExportFixture;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Report;
import cn.easyreport.model.Row;

public class HtmlWordExportTest {
    @Test
    public void writesEditableParagraphsRunsAndContractSpacing() throws Exception {
        Report report = ContractExportFixture.report("<h1 style='text-align:center'>合同标题</h1>"
                + "<p style='text-indent:2em;line-height:1.5;margin-top:6pt;margin-bottom:8pt'>"
                + "正文<strong>加粗</strong><span style='color:#ff0000;font-size:12.5pt'>红字</span>"
                + "<u>下划线</u><s>删除线</s><sup>上标</sup><br>第二行</p>"
                + "<ol start='3'><li>事项甲</li><li>事项乙</li></ol>"
                + "<p><a href='https://example.com'>链接</a></p>");
        try (XWPFDocument document = read(export(report))) {
            XWPFTableCell cell = document.getTables().get(0).getRow(0).getCell(0);
            assertEquals(ParagraphAlignment.CENTER, cell.getParagraphs().get(0).getAlignment());
            XWPFParagraph body = paragraph(cell, "正文");
            assertEquals(480, body.getIndentationFirstLine());
            assertEquals(120, body.getSpacingBefore());
            assertEquals(160, body.getSpacingAfter());
            assertEquals(360, body.getCTP().getPPr().getSpacing().getLine().intValue());
            XWPFRun bold = run(cell, "加粗");
            assertTrue(bold.isBold());
            XWPFRun red = run(cell, "红字");
            assertEquals("FF0000", red.getColor().toUpperCase());
            assertEquals(25, red.getCTR().getRPr().getSz().getVal().intValue());
            assertEquals("宋体", red.getCTR().getRPr().getRFonts().getEastAsia());
            assertTrue(run(cell, "删除线").isStrikeThrough());
            String text = cell.getText();
            assertTrue(text, text.contains("事项甲"));
            assertTrue(text, text.contains("事项乙"));
            assertTrue(text, text.contains("第二行"));
            assertFalse(text, text.contains("<strong>"));
            assertFalse(document.getDocument().xmlText().contains("altChunk"));
            assertTrue(document.getDocument().xmlText().contains("hyperlink"));
        }
    }

    @Test
    public void longContractRemainsOneSplittableTableWithEveryClauseExactlyOnce() throws Exception {
        Report report = ContractExportFixture.report(ContractExportFixture.longContract());
        report.getRootCell().doCompute(report.getContext());
        assertTrue(report.getRootCell().getRow().getRealHeight() > 842);
        byte[] bytes = export(report);
        try (XWPFDocument document = read(bytes)) {
            assertEquals(1, document.getTables().size());
            XWPFTableRow row = document.getTables().get(0).getRow(0);
            assertFalse(row.isCantSplitRow());
            assertEquals(STHeightRule.AT_LEAST, row.getCtRow().getTrPr().getTrHeightArray(0).getHRule());
            assertEquals(24 * 20, row.getHeight());
            String text = row.getCell(0).getText();
            for (int index = 1; index <= 60; index++) {
                String marker = String.format("CLAUSE_%03d", index);
                assertEquals(marker, 1, occurrences(text, marker));
            }
            assertEquals(1, occurrences(text, "END_OF_CONTRACT_060"));
            assertEquals(0, pageBreaks(document));
            assertTrue(row.getCell(0).getParagraphs().size() > 60);
        }
        Path fixture = Paths.get("target", "contract-export", "contract.docx");
        Files.createDirectories(fixture.getParent());
        Files.write(fixture, bytes);
    }

    @Test
    public void previouslyPagedMergeDoesNotDuplicateHtmlContinuation() throws Exception {
        Report report = ContractExportFixture.report("<p>合同正文唯一标识</p>");
        Cell original = report.getRootCell();
        Row second = appendRow(report, "");
        original.setRowSpan(2);
        original.setPageRowSpan(0);
        Cell continuation = original.newCell();
        continuation.setRow(second);
        continuation.setForPaging(true);
        continuation.setData(original.getData());
        report.getRowColCellMap().get(second).put(original.getColumn(), continuation);
        report.setPages(Arrays.asList(new Page(Arrays.asList(original.getRow()), report.getColumns()),
                new Page(Arrays.asList(second), report.getColumns())));
        try (XWPFDocument document = read(export(report))) {
            XWPFTable table = document.getTables().get(0);
            assertEquals(1, occurrences(table.getText(), "合同正文唯一标识"));
            assertEquals(STMerge.RESTART, table.getRow(0).getCell(0).getCTTc().getTcPr().getVMerge().getVal());
            assertEquals(STMerge.CONTINUE, table.getRow(1).getCell(0).getCTTc().getTcPr().getVMerge().getVal());
        }
    }

    @Test
    public void explicitBreaksAndFollowingSignatureRowsArePreserved() throws Exception {
        Report report = ContractExportFixture.report("<p>合同正文</p>");
        report.getRootCell().getRow().setPageBreak(true);
        appendRow(report, "甲方签字 唯一签名");
        try (XWPFDocument document = read(export(report))) {
            assertEquals(2, document.getTables().size());
            assertEquals(1, occurrences(document.getTables().get(0).getText(), "合同正文"));
            assertEquals(1, occurrences(document.getTables().get(1).getText(), "唯一签名"));
            assertEquals(1, pageBreaks(document));
        }
    }

    @Test
    public void ordinaryReportsRetainLiteralTextAndTheirExistingPageBoundaries() throws Exception {
        Report report = ContractExportFixture.report("<b>普通文字</b>");
        Cell cell = report.getRootCell();
        cell.setHtml(false);
        Page page = new Page(report.getRows(), report.getColumns());
        report.setPages(Arrays.asList(page, page));
        try (XWPFDocument document = read(export(report))) {
            assertEquals(2, document.getTables().size());
            assertTrue(document.getTables().get(0).getText().contains("<b>普通文字</b>"));
            assertEquals(1, pageBreaks(document));
        }
    }

    @Test
    public void newspaperColumnsKeepTheirExistingPageLayoutWithRichRuns() throws Exception {
        Report report = ContractExportFixture.report("<p><strong>双栏正文</strong></p>");
        report.getPaper().setColumnEnabled(true);
        report.getPaper().setColumnCount(2);
        Page page = new Page(report.getRows(), report.getColumns());
        report.setPages(Arrays.asList(page, page));
        try (XWPFDocument document = read(export(report))) {
            assertEquals(2, document.getTables().size());
            XWPFTableCell cell = document.getTables().get(0).getRow(0).getCell(0);
            assertTrue(run(cell, "双栏正文").isBold());
            assertEquals(2, document.getDocument().getBody().getSectPr().getCols().getNum().intValue());
        }
    }

    private Row appendRow(Report report, String text) {
        Row row = new Row(report.getRows());
        row.setHeight(24);
        row.setRowKey("r" + (report.getRows().size() + 1));
        report.getRows().add(row);
        Cell cell = new Cell();
        cell.setName("A" + report.getRows().size());
        cell.setRow(row);
        cell.setColumn(report.getColumn(1));
        cell.setValue(new SimpleValue(text));
        cell.setData(text);
        cell.setCellStyle(report.getRootCell().getCellStyle());
        row.getCells().add(cell);
        report.getColumn(1).getCells().add(cell);
        report.addCell(cell);
        return row;
    }

    private byte[] export(Report report) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new WordProducer().produce(report, bytes);
        return bytes.toByteArray();
    }

    private XWPFDocument read(byte[] bytes) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    private XWPFParagraph paragraph(XWPFTableCell cell, String text) {
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            if (paragraph.getText().contains(text)) return paragraph;
        }
        throw new AssertionError("Missing paragraph: " + text);
    }

    private XWPFRun run(XWPFTableCell cell, String text) {
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            for (XWPFRun run : paragraph.getRuns()) {
                if (run.text().contains(text)) return run;
            }
        }
        throw new AssertionError("Missing run: " + text);
    }

    private int occurrences(String text, String marker) {
        return text.split(java.util.regex.Pattern.quote(marker), -1).length - 1;
    }

    private int pageBreaks(XWPFDocument document) {
        int count = 0;
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            for (XWPFRun run : paragraph.getRuns()) {
                for (CTBr br : run.getCTR().getBrArray()) {
                    if (br.getType() == STBrType.PAGE) count++;
                }
            }
        }
        return count;
    }
}
