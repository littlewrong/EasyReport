package cn.easyreport.build.paging;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import cn.easyreport.build.Context;
import cn.easyreport.build.Dataset;
import cn.easyreport.build.ReportBuilder;
import cn.easyreport.definition.Band;
import cn.easyreport.definition.Orientation;
import cn.easyreport.definition.PagingMode;
import cn.easyreport.definition.Paper;
import cn.easyreport.definition.value.SimpleValue;
import cn.easyreport.model.Cell;
import cn.easyreport.model.Column;
import cn.easyreport.model.Report;
import cn.easyreport.model.Row;

/** Exercises already expanded receipts through initial pagination and repagination. */
@RunWith(Parameterized.class)
public class RepeatedFooterPaginationTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> routes() {
        return Arrays.asList(new Object[][] {
                { "initial-fixed", PagingMode.fixrows, true },
                { "repaginate-fixed", PagingMode.fixrows, false },
                { "initial-height", PagingMode.fitpage, true },
                { "repaginate-height", PagingMode.fitpage, false }
        });
    }

    private final PagingMode mode;
    private final boolean initial;

    public RepeatedFooterPaginationTest(String name, PagingMode mode, boolean initial) {
        this.mode = mode;
        this.initial = initial;
    }

    @Test(timeout = 3000)
    public void eachReceiptUsesItsOwnFooterWhenBodyExactlyFillsPage() throws Exception {
        Fixture fixture = new Fixture(mode);
        fixture.receipt("A", 9);
        fixture.receipt("B", 9);
        fixture.receipt("C", 9);
        List<Page> pages = paginate(fixture);
        assertEquals(3, pages.size());
        assertPage(fixture, pages, 0, "A", "A", 1, 9, footers("A"));
        assertPage(fixture, pages, 1, "B", "B", 1, 9, footers("B"));
        assertPage(fixture, pages, 2, "C", "C", 1, 9, footers("C"));
    }

    @Test(timeout = 3000)
    public void everyContinuationPageUsesCurrentReceiptFooterAndPartialLastPageIsKept() throws Exception {
        Fixture fixture = new Fixture(mode);
        fixture.receipt("A", 9);
        fixture.receipt("B", 27);
        fixture.receipt("C", 4);
        List<Page> pages = paginate(fixture);
        assertEquals(5, pages.size());
        assertPage(fixture, pages, 0, "A", "A", 1, 9, footers("A"));
        assertPage(fixture, pages, 1, "B", "B", 1, 9, footers("B"));
        assertPage(fixture, pages, 2, "B", "B", 10, 18, footers("B"));
        assertPage(fixture, pages, 3, "B", "B", 19, 27, footers("B"));
        assertPage(fixture, pages, 4, "C", "C", 1, 4, footers("C"));
    }

    @Test(timeout = 3000)
    public void globalFooterStillRepeatsAndZeroHeightBodyRowsAreIgnored() throws Exception {
        Fixture fixture = new Fixture(mode);
        fixture.headers("A");
        fixture.row("HIDDEN", "hidden-body", null, 0);
        fixture.body("A", 9);
        fixture.headers("B");
        fixture.body("B", 18);
        fixture.headers("C");
        fixture.row("HIDDEN", "another-hidden-body", null, 0);
        fixture.body("C", 4);
        fixture.footer("GLOBAL", 1, 4);
        List<Page> pages = paginate(fixture);
        assertEquals(4, pages.size());
        assertPage(fixture, pages, 0, "A", "A", 1, 9, footers("GLOBAL"));
        assertPage(fixture, pages, 1, "B", "B", 1, 9, footers("GLOBAL"));
        assertPage(fixture, pages, 2, "B", "B", 10, 18, footers("GLOBAL"));
        assertPage(fixture, pages, 3, "C", "C", 1, 4, footers("GLOBAL"));
    }

    @Test(timeout = 3000)
    public void missingFooterKeysKeepTheirExistingGlobalValuesAndOrder() throws Exception {
        Fixture fixture = new Fixture(mode);
        fixture.receipt("A", 9);
        fixture.headers("B");
        fixture.body("B", 9);
        fixture.footer("B", 1, 2);
        fixture.headers("C");
        fixture.body("C", 9);
        fixture.footer("C", 1, 2);
        List<Page> pages = paginate(fixture);
        assertEquals(3, pages.size());
        assertPage(fixture, pages, 0, "A", "A", 1, 9, footers("A"));
        assertPage(fixture, pages, 1, "B", "B", 1, 9,
                Arrays.asList("B-F1", "B-F2", "A-F3", "A-F4"));
        assertPage(fixture, pages, 2, "C", "C", 1, 9,
                Arrays.asList("C-F1", "C-F2", "A-F3", "A-F4"));
    }

    @Test(timeout = 3000)
    public void bodyAfterFooterDoesNotBorrowFooterAcrossNextReceiptHeader() throws Exception {
        Fixture fixture = new Fixture(mode);
        fixture.receipt("A", 9);
        fixture.body("TAIL", 9);
        fixture.receipt("B", 9);
        List<Page> pages = paginate(fixture);
        assertEquals(3, pages.size());
        assertPage(fixture, pages, 0, "A", "A", 1, 9, footers("A"));
        assertPage(fixture, pages, 1, "A", "TAIL", 1, 9, footers("A"));
        assertPage(fixture, pages, 2, "B", "B", 1, 9, footers("B"));
    }

    @Test(timeout = 3000)
    public void hiddenHeaderStillSeparatesFooterGroups() throws Exception {
        Fixture fixture = new Fixture(mode);
        fixture.receipt("A", 9);
        fixture.body("TAIL", 9).setPageBreak(true);
        fixture.row("B-H1", "H1", Band.headerrepeat, 0);
        fixture.body("B", 9);
        fixture.footer("B", 1, 4);
        List<Page> pages = paginate(fixture);
        assertEquals(3, pages.size());
        assertPage(fixture, pages, 0, "A", "A", 1, 9, footers("A"));
        assertPage(fixture, pages, 1, "A", "TAIL", 1, 9, footers("A"));
        // Hidden headers do not replace displayed headers, but delimit footer ownership.
        assertPage(fixture, pages, 2, "A", "B", 1, 9, footers("B"));
    }

    @Test(timeout = 3000)
    public void footerDefinedAboveBodyKeepsPreviousInstanceWithoutHeaders() throws Exception {
        Fixture fixture = new Fixture(mode);
        fixture.report.getPaper().setFixRows(13);
        fixture.report.getPaper().setHeight(144);
        fixture.report.setRepeatHeaderRowHeight(0);
        for (String group : Arrays.asList("A", "B", "C")) {
            fixture.footer(group, 1, 4);
            for (int i = 1; i <= 9; i++) {
                fixture.row(group + "-D" + i, "D" + i, null, 10).setTemplateRowNumber(14);
            }
        }
        List<Page> pages = paginate(fixture);
        assertEquals(3, pages.size());
        for (int index = 0; index < 3; index++) {
            String group = Arrays.asList("A", "B", "C").get(index);
            List<String> expected = new ArrayList<String>();
            for (int i = 1; i <= 9; i++) {
                expected.add(group + "-D" + i);
            }
            expected.addAll(footers(group));
            assertValues(fixture, pages, index, expected);
        }
    }

    @Test(timeout = 3000)
    public void hiddenCurrentFooterDoesNotRevivePreviousReceiptFooter() throws Exception {
        Fixture fixture = new Fixture(mode);
        fixture.receipt("A", 9);
        fixture.headers("B");
        fixture.body("B", 9);
        fixture.footer("B", 1, 3);
        fixture.row("B-F4", "F4", Band.footerrepeat, 0);
        fixture.receipt("C", 9);
        List<Page> pages = paginate(fixture);
        assertEquals(3, pages.size());
        assertPage(fixture, pages, 0, "A", "A", 1, 9, footers("A"));
        assertPage(fixture, pages, 1, "B", "B", 1, 9, footers("B"));
        assertEquals(0, pages.get(1).getRows().get(16).getRealHeight());
        assertPage(fixture, pages, 2, "C", "C", 1, 9, footers("C"));
    }

    private List<Page> paginate(Fixture fixture) throws Exception {
        Report report = fixture.report;
        if (initial) {
            Method recompute = ReportBuilder.class.getDeclaredMethod("recomputeCells", Report.class, Context.class);
            recompute.setAccessible(true);
            recompute.invoke(new ReportBuilder(), report, report.getContext());
            return report.getPages();
        }
        return PagingBuilder.buildPages(report);
    }

    private static List<String> footers(String group) {
        return Arrays.asList(group + "-F1", group + "-F2", group + "-F3", group + "-F4");
    }

    private static void assertPage(Fixture fixture, List<Page> pages, int index,
            String headerGroup, String bodyGroup, int firstBody, int lastBody, List<String> footerValues) {
        List<String> expected = new ArrayList<String>();
        for (int i = 1; i <= 4; i++) {
            expected.add(headerGroup + "-H" + i);
        }
        for (int i = firstBody; i <= lastBody; i++) {
            expected.add(bodyGroup + "-D" + i);
        }
        expected.addAll(footerValues);
        assertValues(fixture, pages, index, expected);
    }

    private static void assertValues(Fixture fixture, List<Page> pages, int index, List<String> expected) {
        List<String> actual = new ArrayList<String>();
        for (Row row : pages.get(index).getRows()) {
            Cell cell = fixture.report.getRowColCellMap().get(row).get(fixture.column);
            actual.add(String.valueOf(cell.getData()));
            assertEquals("row page number", index + 1, row.getPageIndex());
        }
        assertEquals("page " + (index + 1) + " content and repeat-row order", expected, actual);
        assertEquals(pages.size(), fixture.report.getContext().getTotalPages());
    }

    private static class Fixture {
        final Report report = new Report();
        final Column column;

        Fixture(PagingMode mode) {
            report.setRows(new ArrayList<Row>());
            report.setColumns(new ArrayList<Column>());
            column = new Column(report.getColumns());
            column.setWidth(100);
            report.getColumns().add(column);
            Cell root = new Cell();
            root.setName("ROOT");
            report.setRootCell(root);
            Paper paper = new Paper();
            paper.setPagingMode(mode);
            paper.setFixRows(17);
            paper.setTopMargin(0);
            paper.setBottomMargin(0);
            paper.setOrientation(Orientation.portrait);
            // Four 10-point headers + four footers + nine body rows (10 + 1 each).
            paper.setHeight(184);
            report.setPaper(paper);
            report.setRepeatHeaderRowHeight(40);
            report.setRepeatFooterRowHeight(40);
            new Context(new ReportBuilder(), report, Collections.<String, Dataset>emptyMap(),
                    null, new HashMap<String, Object>(), null);
        }

        void receipt(String group, int bodyRows) {
            headers(group);
            body(group, bodyRows);
            footer(group, 1, 4);
        }

        void headers(String group) {
            for (int i = 1; i <= 4; i++) {
                row(group + "-H" + i, "H" + i, Band.headerrepeat, 10);
            }
        }

        Row body(String group, int count) {
            Row last = null;
            for (int i = 1; i <= count; i++) {
                last = row(group + "-D" + i, "D" + i, null, 10);
            }
            return last;
        }

        void footer(String group, int first, int last) {
            for (int i = first; i <= last; i++) {
                row(group + "-F" + i, "F" + i, Band.footerrepeat, 10);
            }
        }

        Row row(String value, String rowKey, Band band, int height) {
            Row row = new Row(report.getRows());
            row.setRowKey(rowKey);
            row.setBand(band);
            row.setHeight(height);
            row.setTemplateRowNumber(band == Band.headerrepeat ? Integer.parseInt(rowKey.substring(1))
                    : band == Band.footerrepeat ? 9 + Integer.parseInt(rowKey.substring(1)) : 5);
            report.getRows().add(row);
            Cell cell = new Cell();
            cell.setName(value);
            cell.setRow(row);
            cell.setColumn(column);
            cell.setValue(new SimpleValue(value));
            cell.setData(value);
            cell.setFormatData(value);
            row.getCells().add(cell);
            column.getCells().add(cell);
            report.addCell(cell);
            List<Row> repeated = band == Band.headerrepeat ? report.getHeaderRepeatRows()
                    : band == Band.footerrepeat ? report.getFooterRepeatRows() : null;
            if (repeated != null) {
                boolean exists = false;
                for (Row existing : repeated) {
                    exists |= rowKey.equals(existing.getRowKey());
                }
                if (!exists) {
                    repeated.add(row);
                }
            }
            return row;
        }
    }
}
