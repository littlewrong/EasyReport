package cn.easyreport.console.importexcel;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import cn.easyreport.console.RequestHolder;
import cn.easyreport.console.cache.TempObjectCache;
import cn.easyreport.definition.ReportDefinition;

public class ImportExcelSecurityTest {
    private static final String BOUNDARY = "easyreport-upload-test-boundary";

    @Test
    public void importsRealXlsAndXlsxAndDeletesTemporaryFiles() throws Exception {
        for (boolean xlsx : Arrays.asList(false, true)) {
            byte[] bytes;
            try (Workbook book = workbook(xlsx)) {
                book.createSheet("Report").createRow(2).createCell(2).setCellValue("hello");
                bytes = serialize(book);
            }
            Request request = request("report" + (xlsx ? ".XLSX" : ".XLS"), bytes);
            Response response = invoke(request);
            assertEquals(response.text(), 200, response.status);
            assertTrue(response.text().contains("\"result\":true"));
            RequestHolder.setRequest(request.proxy());
            try {
                ReportDefinition report = (ReportDefinition) TempObjectCache.getObject("classpath:template/template.easyreport.xml");
                assertNotNull(report);
                assertEquals(3, report.getRows().size());
                assertEquals(3, report.getColumns().size());
                assertEquals(9, report.getCells().size());
            } finally {
                RequestHolder.clean();
            }
        }
    }

    @Test
    public void rejectsWrongExtensionsPathNamesAndControlCharacters() throws Exception {
        byte[] xls = smallWorkbook(false);
        for (String name : Arrays.asList("shell.jsp", "../report.xls", "folder/report.xls",
                "C:\\report.xls", "report\u0000.xls", "report\n.xls", "")) {
            try {
                assertRejected(request(name, xls));
            } catch (AssertionError failure) {
                throw new AssertionError("Filename: " + name, failure);
            }
        }
        try {
            ExcelUploadValidator.validateName("report.xls ");
            fail("Untrimmed filenames must be rejected by the filename validator");
        } catch (IOException expected) { }
    }

    @Test
    public void rejectsEmptyNonExcelAndMismatchedActualFormats() throws Exception {
        assertRejected(request("empty.xls", new byte[0]));
        assertRejected(request("fake.xls", "<easyreport/>".getBytes(StandardCharsets.UTF_8)));
        assertRejected(request("fake.xlsx", "<easyreport/>".getBytes(StandardCharsets.UTF_8)));
        assertRejected(request("mismatch.xlsx", smallWorkbook(false)));
        assertRejected(request("mismatch.xls", smallWorkbook(true)));
    }

    @Test
    public void acceptsOnlyOneExpectedFilePartAndStopsAtTheNextPart() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, "_excel_file", "first.xls", smallWorkbook(false));
        for (int i = 0; i < 10000; i++) part(body, "extra" + i, null, new byte[0]);
        finish(body);
        Request request = new Request(body.toByteArray());
        assertRejected(request);
        assertTrue("Must reject parts without accumulating or draining all of them", request.stream.readBytes < request.body.length / 2);
        body.reset();
        part(body, "wrong_field", "report.xls", smallWorkbook(false));
        finish(body);
        assertRejected(new Request(body.toByteArray()));
    }

    @Test
    public void rejectsDuplicateFilesAndMissingFile() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, "_excel_file", "first.xls", smallWorkbook(false));
        part(body, "_excel_file", "second.xls", smallWorkbook(false));
        finish(body);
        assertRejected(new Request(body.toByteArray()));
        body.reset();
        finish(body);
        assertRejected(new Request(body.toByteArray()));
    }

    @Test
    public void requiresPostAndMultipart() throws Exception {
        Request request = request("report.xls", smallWorkbook(false));
        request.method = "GET";
        Response response = invoke(request);
        assertEquals(405, response.status);
        assertEquals("POST", response.allow);
        request = request("report.xls", smallWorkbook(false));
        request.contentType = "application/octet-stream";
        assertRejected(request);
    }

    @Test
    public void enforcesFileLimitEvenForUnknownRequestLength() throws Exception {
        Request request = request("large.xls", new byte[(int) ImportExcelServletAction.MAX_FILE_BYTES + 1]);
        request.declaredLength = -1;
        assertRejected(request);
    }

    @Test
    public void enforcesTotalRequestLimitBeforeCreatingTemporaryFile() throws Exception {
        Request request = request("report.xls", smallWorkbook(false));
        request.declaredLength = ImportExcelServletAction.MAX_REQUEST_BYTES + 1;
        TestAction action = new TestAction();
        assertEquals(400, invoke(request, action).status);
        assertTrue(action.directories.isEmpty());
    }

    @Test
    public void rejectsDoctypeInXlsxBeforePoiAndPreservesGlobalZipSettings() throws Exception {
        double ratio = ZipSecureFile.getMinInflateRatio();
        long maxEntry = ZipSecureFile.getMaxEntrySize();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(body)) {
            zipPart(zip, "[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
            zipPart(zip, "xl/workbook.xml", "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///private-do-not-read'>]><workbook>&x;</workbook>".getBytes(StandardCharsets.UTF_8));
        }
        assertRejected(request("doctype.xlsx", body.toByteArray()));
        assertEquals(ratio, ZipSecureFile.getMinInflateRatio(), 0);
        assertEquals(maxEntry, ZipSecureFile.getMaxEntrySize());
    }

    @Test
    public void boundsZipEntryCountAndInflatedData() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(body)) {
            for (int i = 0; i < 257; i++) zipPart(zip, "item" + i + ".bin", new byte[0]);
        }
        assertRejected(request("entries.xlsx", body.toByteArray()));
        body.reset();
        try (ZipOutputStream zip = new ZipOutputStream(body)) {
            zipPart(zip, "large.bin", new byte[10 * 1024 * 1024 + 1]);
        }
        assertRejected(request("expanded.xlsx", body.toByteArray()));
    }

    @Test
    public void validatesXmlPartsRenamedToBinaryExtensionsUsingContentTypes() throws Exception {
        byte[] original = smallWorkbook(true);
        Response normal = invoke(request("renamed.xlsx", renameWorksheet(original, false, false)));
        assertEquals(normal.text(), 200, normal.status);
        assertRejected(request("doctype-renamed.xlsx", renameWorksheet(original, true, false)));
        // A relationship cannot hide an XML worksheet behind a false binary MIME type.
        assertRejected(request("false-content-type.xlsx", renameWorksheet(original, true, true)));
    }

    private static byte[] renameWorksheet(byte[] original, boolean doctype, boolean binaryType) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(original));
                ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int read;
                while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
                String name = entry.getName();
                byte[] content = bytes.toByteArray();
                if (name.equals("[Content_Types].xml") || name.equals("xl/_rels/workbook.xml.rels")) {
                    String xml = new String(content, StandardCharsets.UTF_8).replace("worksheets/sheet1.xml", "worksheets/sheet1.bin");
                    if (binaryType && name.equals("[Content_Types].xml")) {
                        xml = xml.replace("application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml", "application/octet-stream");
                    }
                    content = xml.getBytes(StandardCharsets.UTF_8);
                }
                if (name.equals("xl/worksheets/sheet1.xml")) {
                    name = "xl/worksheets/sheet1.bin";
                    if (doctype) {
                        String xml = new String(content, StandardCharsets.UTF_8);
                        int afterDeclaration = xml.startsWith("<?xml") ? xml.indexOf("?>") + 2 : 0;
                        xml = xml.substring(0, afterDeclaration)
                                + "<!DOCTYPE worksheet [<!ENTITY x SYSTEM 'file:///private-do-not-read'>]>"
                                + xml.substring(afterDeclaration);
                        content = xml.getBytes(StandardCharsets.UTF_8);
                    }
                }
                zipPart(zip, name, content);
            }
        }
        return output.toByteArray();
    }

    @Test
    public void importsXlsxWithBinaryThumbnailRelationship() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(smallWorkbook(true)));
                ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int read;
                while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
                byte[] content = bytes.toByteArray();
                if (entry.getName().equals("[Content_Types].xml")) {
                    content = new String(content, StandardCharsets.UTF_8).replace("</Types>",
                            "<Default Extension=\"png\" ContentType=\"image/png\"/></Types>").getBytes(StandardCharsets.UTF_8);
                } else if (entry.getName().equals("_rels/.rels")) {
                    content = new String(content, StandardCharsets.UTF_8).replace("</Relationships>",
                            "<Relationship Id=\"thumbnail999\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail\""
                            + " Target=\"docProps/thumbnail.png\"/></Relationships>").getBytes(StandardCharsets.UTF_8);
                }
                zipPart(zip, entry.getName(), content);
            }
            zipPart(zip, "docProps/thumbnail.png", Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jA2sAAAAASUVORK5CYII="));
        }
        Response response = invoke(request("thumbnail.xlsx", output.toByteArray()));
        assertEquals(response.text(), 200, response.status);
    }

    @Test
    public void rejectsSparseDimensionsAndDenseExpansionBeforeConversion() throws Exception {
        for (boolean xlsx : Arrays.asList(false, true)) {
            try (Workbook book = workbook(xlsx)) {
                book.createSheet().createRow(ExcelImportLimits.MAX_ROWS).createCell(0).setCellValue("row");
                expectParserFailure(xlsx, serialize(book));
            }
            if (xlsx) {
                try (Workbook book = workbook(true)) {
                    book.createSheet().createRow(0).createCell(ExcelImportLimits.MAX_COLUMNS).setCellValue("column");
                    expectParserFailure(true, serialize(book));
                }
            }
            try (Workbook book = workbook(xlsx)) {
                book.createSheet().createRow(999).createCell(200).setCellValue("dense grid");
                expectParserFailure(xlsx, serialize(book));
            }
        }
    }

    @Test
    public void boundsMergedRegionsAndMergeScanWork() throws Exception {
        try (Workbook book = workbook(true)) {
            Sheet sheet = book.createSheet();
            sheet.createRow(499).createCell(199).setCellValue("large report");
            for (int i = 0; i < 101; i++) sheet.addMergedRegion(new CellRangeAddress(i, i, 0, 1));
            expectParserFailure(true, serialize(book));
        }
        try (Workbook book = workbook(true)) {
            Sheet sheet = book.createSheet();
            sheet.createRow(0).createCell(1).setCellValue("merge beyond data");
            sheet.addMergedRegion(new CellRangeAddress(0, 10000, 0, 1));
            expectParserFailure(true, serialize(book));
        }
    }

    @Test
    public void bothParsersCloseTheInputWhenWorkbookConstructionFails() throws Exception {
        for (boolean xlsx : Arrays.asList(false, true)) expectParserFailure(xlsx, new byte[] {1, 2, 3});
    }

    @Test
    public void includesMergedBlankRowsAndColumnsWithinTheLimits() throws Exception {
        for (boolean xlsx : Arrays.asList(false, true)) {
            try (Workbook book = workbook(xlsx)) {
                Sheet sheet = book.createSheet();
                sheet.createRow(0).createCell(0).setCellValue("merged");
                sheet.addMergedRegion(new CellRangeAddress(0, 2, 0, 2));
                ReportDefinition report = (xlsx ? new XSSFExcelParser() : new HSSFExcelParser())
                        .parse(new ByteArrayInputStream(serialize(book)));
                assertEquals(3, report.getRows().size());
                assertEquals(3, report.getColumns().size());
            }
        }
    }

    private void expectParserFailure(boolean xlsx, byte[] bytes) throws Exception {
        final boolean[] closed = {false};
        InputStream source = new ByteArrayInputStream(bytes) {
            @Override public void close() throws IOException { closed[0] = true; super.close(); }
        };
        try {
            (xlsx ? new XSSFExcelParser() : new HSSFExcelParser()).parse(source);
            fail("Expected invalid or excessive workbook to be rejected");
        } catch (Exception expected) {
            assertTrue("Input must close on parse failure", closed[0]);
        }
    }

    private static Workbook workbook(boolean xlsx) { return xlsx ? new XSSFWorkbook() : new HSSFWorkbook(); }

    private static byte[] serialize(Workbook book) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        book.write(bytes);
        return bytes.toByteArray();
    }

    private static byte[] smallWorkbook(boolean xlsx) throws IOException {
        try (Workbook book = workbook(xlsx)) {
            book.createSheet().createRow(0).createCell(0).setCellValue("hello");
            return serialize(book);
        }
    }

    private static void zipPart(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static Request request(String name, byte[] bytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(body, "_excel_file", name, bytes);
        finish(body);
        return new Request(body.toByteArray());
    }

    private static void part(ByteArrayOutputStream out, String field, String name, byte[] bytes) throws IOException {
        String header = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field + "\""
                + (name == null ? "" : "; filename=\"" + name + "\"") + "\r\nContent-Type: application/octet-stream\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void finish(ByteArrayOutputStream out) throws IOException {
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRejected(Request request) throws Exception {
        Response response = invoke(request);
        assertEquals(response.text(), 400, response.status);
        assertTrue(response.text().contains("\"result\":false"));
        assertFalse(response.text().contains("Exception"));
        assertFalse(response.text().contains("private-do-not-read"));
    }

    private static Response invoke(Request request) throws Exception { return invoke(request, new TestAction()); }

    private static Response invoke(Request request, TestAction action) throws Exception {
        Response response = new Response();
        HttpServletRequest httpRequest = request.proxy();
        RequestHolder.setRequest(httpRequest);
        try {
            action.execute(httpRequest, proxy(HttpServletResponse.class, response));
        } finally {
            RequestHolder.clean();
            for (Path path : action.directories) assertFalse("Temporary directory must be removed", Files.exists(path));
        }
        if ("POST".equals(request.method) && request.contentType.startsWith("multipart/")) {
            assertTrue("Request stream must close", request.stream.closed);
        }
        return response;
    }

    private static final class TestAction extends ImportExcelServletAction {
        private final List<Path> directories = new ArrayList<Path>();
        @Override Path createUploadDirectory() throws IOException {
            Path path = super.createUploadDirectory();
            directories.add(path);
            return path;
        }
    }

    private static final class Request implements InvocationHandler {
        private final byte[] body;
        private final RequestInput stream;
        private String method = "POST";
        private String contentType = "multipart/form-data; boundary=" + BOUNDARY;
        private long declaredLength;
        private final HttpSession session = ImportExcelSecurityTest.proxy(HttpSession.class, (object, method, arguments) ->
                method.getName().equals("getId") ? "import-test-" + System.identityHashCode(this) : defaultValue(method.getReturnType()));

        private Request(byte[] body) { this.body = body; this.stream = new RequestInput(body); this.declaredLength = body.length; }
        private HttpServletRequest proxy() { return ImportExcelSecurityTest.proxy(HttpServletRequest.class, this); }

        @Override public Object invoke(Object object, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "getMethod": return this.method;
                case "getContentType": return contentType;
                case "getInputStream": return stream;
                case "getContentLength": return (int) declaredLength;
                case "getContentLengthLong": return declaredLength;
                case "getCharacterEncoding": return "UTF-8";
                case "getHeader": return "Content-length".equalsIgnoreCase((String) arguments[0]) && declaredLength >= 0 ? Long.toString(declaredLength) : null;
                case "getSession": return session;
                default: return defaultValue(method.getReturnType());
            }
        }
    }

    private static final class RequestInput extends ServletInputStream {
        private final ByteArrayInputStream source;
        private boolean closed;
        private int readBytes;
        private RequestInput(byte[] body) { source = new ByteArrayInputStream(body); }
        @Override public int read() { int value = source.read(); if (value >= 0) readBytes++; return value; }
        @Override public int read(byte[] bytes, int offset, int length) {
            int read = source.read(bytes, offset, length); if (read > 0) readBytes += read; return read;
        }
        @Override public void close() { closed = true; }
        @Override public boolean isFinished() { return source.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
    }

    private static final class Response implements InvocationHandler {
        private int status = 200;
        private String allow;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ServletOutputStream stream = new ServletOutputStream() {
            @Override public void write(int value) { bytes.write(value); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
        };
        private String text() { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); }
        @Override public Object invoke(Object object, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "setStatus": status = (Integer) arguments[0]; return null;
                case "setHeader": if ("Allow".equals(arguments[0])) allow = (String) arguments[1]; return null;
                case "getOutputStream": return stream;
                default: return defaultValue(method.getReturnType());
            }
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
