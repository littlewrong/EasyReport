package cn.easyreport.console.importexcel;

import java.io.IOException;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;

/** Resource limits for the dense report representation built by the importers. */
final class ExcelImportLimits {
    static final int MAX_ROWS = 10000;
    static final int MAX_COLUMNS = 256;
    // Match the report parser's cell limit; imports must remain usable as reports.
    static final long MAX_CELLS = 100000;
    static final int MAX_MERGED_REGIONS = 1000;
    private static final long MAX_MERGE_CHECKS = 10000000;

    private ExcelImportLimits() { }

    static void validateWorkbook(Workbook book) throws IOException {
        if (book.getNumberOfSheets() < 1 || book.getNumberOfSheets() > 32
                || book.getNumCellStyles() > 10000) {
            throw invalidWorkbook();
        }
        long totalCells = 0;
        for (int i = 0; i < book.getNumberOfSheets(); i++) {
            Sheet sheet = book.getSheetAt(i);
            int merges = sheet.getNumMergedRegions();
            if (merges > MAX_MERGED_REGIONS) throw invalidWorkbook();
            int rows = rowCount(sheet);
            int columns = columnCount(sheet);
            long cells = (long) rows * columns;
            totalCells += cells;
            if (rows > MAX_ROWS || columns > MAX_COLUMNS || totalCells > MAX_CELLS
                    || cells * merges > MAX_MERGE_CHECKS) {
                throw invalidWorkbook();
            }
            for (int j = 0; j < merges; j++) {
                CellRangeAddress range = sheet.getMergedRegion(j);
                if (range.getFirstRow() < 0 || range.getFirstColumn() < 0
                        || range.getFirstRow() > range.getLastRow()
                        || range.getFirstColumn() > range.getLastColumn()) {
                    throw invalidWorkbook();
                }
            }
        }
    }

    static int columnCount(Sheet sheet) {
        int columns = 0;
        for (Row row : sheet) {
            // Physical cell counts miss sparse cells at a very large column index.
            columns = Math.max(columns, row.getLastCellNum());
        }
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            columns = Math.max(columns, sheet.getMergedRegion(i).getLastColumn() + 1);
        }
        return columns;
    }

    static int rowCount(Sheet sheet) {
        int rows = sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            rows = Math.max(rows, sheet.getMergedRegion(i).getLastRow() + 1);
        }
        return rows;
    }

    private static IOException invalidWorkbook() {
        return new IOException("Excel workbook exceeds the import limits.");
    }
}
