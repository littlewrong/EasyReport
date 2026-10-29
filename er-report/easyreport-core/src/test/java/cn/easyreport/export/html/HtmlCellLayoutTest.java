package cn.easyreport.export.html;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import cn.easyreport.build.Context;
import cn.easyreport.build.Dataset;
import cn.easyreport.build.HideRowColumnBuilder;
import cn.easyreport.definition.CellStyle;
import cn.easyreport.definition.value.SimpleValue;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Column;
import cn.easyreport.model.Report;
import cn.easyreport.model.Row;

public class HtmlCellLayoutTest {
    private static final String SENTENCE = "One two three four five six seven eight nine ten "
            + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen.";

    @Test
    public void constrainedWidthWrapsVisibleContent() {
        Fixture narrow = fixture(SENTENCE, new int[]{70}, new int[]{1});
        Fixture wide = fixture(SENTENCE, new int[]{350}, new int[]{1});
        narrow.compute();
        wide.compute();
        assertTrue(narrow.height() > wide.height());
        assertTrue(wide.height() > 1);
    }

    @Test
    public void inlineFontSizesAndParagraphsAffectRequiredHeight() {
        Fixture ordinary = fixture("Text", new int[]{300}, new int[]{1});
        Fixture large = fixture("<span style='font-size:32pt'>Text</span>", new int[]{300}, new int[]{1});
        Fixture paragraphs = fixture("<p>Text</p><p>Text</p><div>Text</div>", new int[]{300}, new int[]{1});
        ordinary.compute();
        large.compute();
        paragraphs.compute();
        assertTrue(large.height() > ordinary.height());
        assertTrue(paragraphs.height() >= ordinary.height() * 2);
    }

    @Test
    public void headingsUseTheSharedBrowserAndPrintStyles() {
        Fixture ordinary = fixture("Text", new int[]{300}, new int[]{1});
        Fixture heading = fixture("<h1>Text</h1>", new int[]{300}, new int[]{1});
        ordinary.compute();
        heading.compute();
        assertTrue(heading.height() > ordinary.height());
    }

    @Test
    public void longWordsAndCjkTextWrapInsideTheAvailableWidth() {
        for (String content : new String[]{
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
                "这是一段用于测试富文本单元格自动换行和分页高度的中文内容，不应该改变原始数据。"}) {
            Fixture narrow = fixture(content, new int[]{45}, new int[]{1});
            Fixture wide = fixture(content, new int[]{400}, new int[]{1});
            narrow.compute();
            wide.compute();
            assertTrue(narrow.height() > wide.height());
        }
    }

    @Test
    public void listsAndExplicitBreaksAllocateMultipleLines() {
        Fixture single = fixture("Item", new int[]{300}, new int[]{1});
        Fixture list = fixture("<ul><li>Item</li><li>Item</li><li>Item</li></ul>", new int[]{300}, new int[]{1});
        Fixture breaks = fixture("Item<br>Item<br>Item", new int[]{300}, new int[]{1});
        single.compute();
        list.compute();
        breaks.compute();
        assertTrue(list.height() >= single.height() * 2);
        assertTrue(breaks.height() >= single.height() * 2);
    }

    @Test
    public void measuresFormatDataWithoutChangingEitherStoredValue() {
        Fixture fixture = fixture("raw source", new int[]{70}, new int[]{1});
        String raw = new String("raw source");
        String formatted = new String("<p>" + SENTENCE + "</p><p>More text</p>");
        fixture.cell.setData(raw);
        fixture.cell.setFormatData(formatted);
        fixture.compute();
        assertSame(raw, fixture.cell.getData());
        assertSame(formatted, fixture.cell.getFormatData());
        assertTrue(fixture.height() > 1);
    }

    @Test
    public void hiddenRowsAndEntirelyHiddenColumnsRemainHidden() {
        Fixture zero = fixture(SENTENCE, new int[]{70}, new int[]{0});
        zero.compute();
        assertEquals(0, zero.height());
        Fixture hidden = fixture(SENTENCE, new int[]{70}, new int[]{10});
        hidden.cell.getRow().setHide(true);
        hidden.compute();
        assertEquals(10, hidden.height());
        Fixture hiddenColumn = fixture(SENTENCE, new int[]{0}, new int[]{10});
        hiddenColumn.compute();
        assertEquals(10, hiddenColumn.height());
        Fixture processedColumn = fixture(SENTENCE, new int[]{0, 70}, new int[]{10});
        processedColumn.cell.getColumn().setHide(true);
        processedColumn.compute();
        assertEquals(10, processedColumn.height());
    }

    @Test
    public void horizontalMergesUseTheirCombinedVisibleWidth() {
        Fixture merged = fixture(SENTENCE, new int[]{70, 70}, new int[]{1});
        merged.cell.setColSpan(2);
        Fixture equivalent = fixture(SENTENCE, new int[]{140}, new int[]{1});
        Fixture single = fixture(SENTENCE, new int[]{70}, new int[]{1});
        merged.compute();
        equivalent.compute();
        single.compute();
        assertEquals(equivalent.height(), merged.height());
        assertTrue(single.height() > merged.height());
    }

    @Test
    public void processedHiddenColumnsDoNotConsumeTheReducedMergeSpan() {
        Fixture hidden = fixture(SENTENCE, new int[]{70, 0, 70}, new int[]{1});
        hidden.report.getColumn(2).setHide(true);
        hidden.cell.setColSpan(2);
        Fixture equivalent = fixture(SENTENCE, new int[]{140}, new int[]{1});
        hidden.compute();
        equivalent.compute();
        assertEquals(equivalent.height(), hidden.height());
    }

    @Test
    public void verticalMergesGrowOnlyByTheMissingCombinedHeight() {
        Fixture reference = fixture("<p>One</p><p>Two</p><p>Three</p>", new int[]{200}, new int[]{1});
        reference.compute();
        Fixture merged = fixture("<p>One</p><p>Two</p><p>Three</p>", new int[]{200}, new int[]{1, 5});
        merged.cell.setRowSpan(2);
        merged.compute();
        assertEquals(reference.height(), merged.height() + merged.report.getRow(2).getRealHeight());
        assertEquals(5, merged.report.getRow(2).getRealHeight());
        int firstHeight = merged.height();
        merged.compute();
        assertEquals(firstHeight, merged.height());
    }

    @Test
    public void ampleMergedRowsAreNotGrownAndZeroRowsAreNotResurrected() {
        Fixture ample = fixture("<p>One</p><p>Two</p>", new int[]{200}, new int[]{10, 200});
        ample.cell.setRowSpan(2);
        ample.compute();
        assertEquals(10, ample.height());
        assertEquals(200, ample.report.getRow(2).getRealHeight());
        Fixture zero = fixture(SENTENCE, new int[]{70}, new int[]{1, 0});
        zero.cell.setRowSpan(2);
        zero.compute();
        assertEquals(0, zero.report.getRow(2).getRealHeight());
    }

    @Test
    public void repeatedMeasurementsAndExistingLargerRowsStayStable() {
        Fixture fixture = fixture(SENTENCE, new int[]{70}, new int[]{1});
        fixture.compute();
        int height = fixture.height();
        for (int index = 0; index < 4; index++) {
            fixture.compute();
            assertEquals(height, fixture.height());
        }
        fixture.cell.getRow().setRealHeight(height + 100);
        fixture.compute();
        assertEquals(height + 100, fixture.height());
        assertEquals(SENTENCE, fixture.cell.getData());
        assertEquals(SENTENCE, fixture.cell.getFormatData());
    }

    @Test
    public void customColumnFontSizeOverridesRowAndCellStyles() {
        Fixture fixture = fixture("Text", new int[]{300}, new int[]{1});
        fixture.compute();
        int normal = fixture.height();
        CellStyle customCell = new CellStyle();
        customCell.setFontSize(18);
        fixture.cell.setCustomCellStyle(customCell);
        CellStyle customRow = new CellStyle();
        customRow.setFontSize(24);
        fixture.cell.getRow().setCustomCellStyle(customRow);
        CellStyle customColumn = new CellStyle();
        customColumn.setFontSize(32);
        fixture.cell.getColumn().setCustomCellStyle(customColumn);
        fixture.compute();
        Fixture equivalent = fixture("Text", new int[]{300}, new int[]{1});
        equivalent.cell.getCellStyle().setFontSize(32);
        equivalent.compute();
        assertTrue(fixture.height() > normal);
        assertEquals(equivalent.height(), fixture.height());
    }

    @Test
    public void htmlLayoutDoesNotDependOnPlainTextWrapToggle() {
        Fixture fixture = fixture(SENTENCE, new int[]{70}, new int[]{1});
        fixture.cell.getCellStyle().setWrapCompute(false);
        fixture.cell.doDataWrapCompute(fixture.context);
        assertTrue(fixture.height() > 1);
        assertEquals(SENTENCE, fixture.cell.getFormatData());
    }

    @Test
    public void growingHtmlBandsRefreshesCachedPaginationHeights() {
        for (int band = 0; band < 4; band++) {
            Fixture fixture = fixture(SENTENCE, new int[]{70}, new int[]{1, 5});
            Report report = fixture.report;
            List<Row> rows;
            switch (band) {
                case 0:
                    rows = report.getHeaderRepeatRows();
                    report.setRepeatHeaderRowHeight(6);
                    break;
                case 1:
                    rows = report.getFooterRepeatRows();
                    report.setRepeatFooterRowHeight(6);
                    break;
                case 2:
                    rows = report.getTitleRows();
                    report.setTitleRowsHeight(6);
                    break;
                default:
                    rows = report.getSummaryRows();
                    report.setSummaryRowsHeight(6);
            }
            rows.addAll(report.getRows());
            fixture.compute();
            int expected = fixture.height() + 5;
            assertTrue(expected > 6);
            assertEquals(band == 0 ? expected : 0, report.getRepeatHeaderRowHeight());
            assertEquals(band == 1 ? expected : 0, report.getRepeatFooterRowHeight());
            assertEquals(band == 2 ? expected : 0, report.getTitleRowsHeight());
            assertEquals(band == 3 ? expected : 0, report.getSummaryRowsHeight());
            fixture.compute();
            assertEquals(expected, fixture.height() + 5);
        }
    }

    private Fixture fixture(String html, int[] widths, int[] heights) {
        Report report = new Report();
        List<Row> rows = new ArrayList<Row>();
        for (int height : heights) {
            Row row = new Row(rows);
            row.setHeight(height);
            rows.add(row);
        }
        report.setRows(rows);
        List<Column> columns = new ArrayList<Column>();
        for (int width : widths) {
            Column column = new Column(columns);
            column.setWidth(width);
            columns.add(column);
        }
        report.setColumns(columns);
        Cell cell = new Cell();
        cell.setName("A1");
        cell.setRow(rows.get(0));
        cell.setColumn(columns.get(0));
        cell.setHtml(true);
        cell.setValue(new SimpleValue(html));
        cell.setData(html);
        CellStyle style = new CellStyle();
        style.setFontFamily("Dialog");
        style.setFontSize(12);
        cell.setCellStyle(style);
        report.setRootCell(cell);
        report.addCell(cell);
        Context context = new Context(null, report, Collections.<String, Dataset>emptyMap(), null,
                Collections.<String, Object>emptyMap(), new HideRowColumnBuilder());
        return new Fixture(report, cell, context);
    }

    private static final class Fixture {
        final Report report;
        final Cell cell;
        final Context context;

        Fixture(Report report, Cell cell, Context context) {
            this.report = report;
            this.cell = cell;
            this.context = context;
        }

        void compute() {
            HtmlCellLayout.computeHeight(cell, context);
        }

        int height() {
            return cell.getRow().getRealHeight();
        }
    }
}
