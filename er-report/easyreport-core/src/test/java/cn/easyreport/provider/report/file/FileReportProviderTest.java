package cn.easyreport.provider.report.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import cn.easyreport.exception.ReportFileException;
import cn.easyreport.provider.report.ReportFile;

public class FileReportProviderTest {
    private static final String ORIGINAL = "<ureport>original sentinel</ureport>";
    private static final String REPLACEMENT = "<ureport>replacement</ureport>";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private FileReportProvider provider;
    private Path reportRoot;
    private Path outsideFile;
    private Path siblingRoot;
    private Path siblingFile;

    @Before
    public void setUp() throws IOException {
        reportRoot = temporaryFolder.newFolder("reports").toPath();
        outsideFile = temporaryFolder.getRoot().toPath().resolve("outside.xml");
        siblingRoot = temporaryFolder.newFolder("reports-other").toPath();
        siblingFile = siblingRoot.resolve("outside.xml");
        write(outsideFile, ORIGINAL);
        write(siblingFile, ORIGINAL);
        provider = new FileReportProvider();
        provider.setFileStoreDir(reportRoot.toString());
    }

    @Test
    public void readsWritesAndDeletesXmlReportsWithAndWithoutPrefix() throws IOException {
        String[] names = {"report.xml", "report.ureport.xml", "月度 销售报表.xml"};
        for (String name : names) {
            provider.saveReport("file:" + name, ORIGINAL);
            assertEquals(ORIGINAL, readReport(name));
            provider.saveReport(name, REPLACEMENT);
            assertEquals(REPLACEMENT, readReport("file:" + name));
            provider.deleteReport("file:" + name);
            assertFalse(Files.exists(reportRoot.resolve(name)));
            expectFailure(404, () -> provider.loadReport(name));
        }
    }

    @Test
    public void allowsReportsInExistingRelativeSubdirectories() throws IOException {
        Files.createDirectories(reportRoot.resolve("年度 报表").resolve("销售"));
        String name = "年度 报表/销售/明细.ureport.xml";
        provider.saveReport("file:" + name, ORIGINAL);
        assertEquals(ORIGINAL, readReport(name));
        provider.deleteReport(name);
        assertFalse(Files.exists(reportRoot.resolve(name)));
    }

    @Test
    public void traversalCannotReadOverwriteOrDeleteOutsideReports() throws IOException {
        String[] names = {
            "../outside.xml",
            "file:../outside.xml",
            "file:..\\outside.xml",
            "file:sub/../../outside.xml",
            "file:sub\\..\\..\\outside.xml",
            "file:sub/..\\../outside.xml",
            "file:../reports-other/outside.xml"
        };
        for (String name : names) {
            assertAllOperationsReject(name);
            assertOutsideFilesUnchanged();
        }
    }

    @Test
    public void rejectsParentSegmentsEvenWhenTheyWouldResolveInsideRoot() throws IOException {
        Files.createDirectory(reportRoot.resolve("nested"));
        Path report = reportRoot.resolve("report.xml");
        write(report, ORIGINAL);
        assertAllOperationsReject("file:nested/../report.xml");
        assertAllOperationsReject("file:nested\\..\\report.xml");
        assertEquals(ORIGINAL, read(report));
    }

    @Test
    public void rejectsAbsolutePathsIncludingSiblingWithSameDirectoryPrefix() throws IOException {
        String[] names = {
            outsideFile.toAbsolutePath().toString(),
            "file:" + outsideFile.toAbsolutePath(),
            "file:" + siblingFile.toAbsolutePath(),
            "file:/outside.xml"
        };
        for (String name : names) {
            assertAllOperationsReject(name);
            assertOutsideFilesUnchanged();
        }
    }

    @Test
    public void rejectsWindowsDriveUncAndAlternateDataStreamNamesOnEveryPlatform() {
        String[] names = {
            "file:C:\\outside.xml",
            "file:C:/outside.xml",
            "file:C:outside.xml",
            "file:\\outside.xml",
            "file:\\\\server\\share\\outside.xml",
            "file://server/share/outside.xml",
            "file:\\\\?\\C:\\outside.xml",
            "file:report.xml:stream.xml",
            "file:report.xml::$DATA",
            "file:CON.xml", "file:NUL.xml", "file:COM1.xml", "file:LPT9.xml",
            "file:COM¹.xml", "file:COM².xml", "file:COM³.xml",
            "file:LPT¹.xml", "file:LPT².xml", "file:LPT³.xml",
            "file:folder./outside.xml", "file:folder /outside.xml"
        };
        for (String name : names) {
            assertAllOperationsReject(name);
        }
    }

    @Test
    public void rejectsEmptyControlCharacterAndNonXmlNames() {
        String[] names = {
            null, "", " ", "file:", "file: ",
            "file:bad\u0000.xml", "file:bad\n.xml", "file:bad\r.xml", "file:bad\t.xml",
            "file:application.yml", "file:application.properties", "file:source.jar",
            "file:report.xml.bak", "file:report.xml/", "file:report.xml\\"
        };
        for (String name : names) {
            assertAllOperationsReject(name);
        }
    }

    @Test
    public void existingNonXmlFilesCannotBeReadOverwrittenOrDeleted() throws IOException {
        Path config = reportRoot.resolve("application.yml");
        write(config, ORIGINAL);
        assertAllOperationsReject("file:application.yml");
        assertEquals(ORIGINAL, read(config));
    }

    @Test
    public void directoriesNamedXmlCannotBeReadOverwrittenOrDeleted() throws IOException {
        Path directory = Files.createDirectory(reportRoot.resolve("directory.xml"));
        Path child = directory.resolve("child.xml");
        write(child, ORIGINAL);
        assertAllOperationsReject("file:directory.xml");
        assertTrue(Files.isDirectory(directory));
        assertEquals(ORIGINAL, read(child));
    }

    @Test
    public void missingReportErrorsHaveFixedMessagesAndNoPathBearingCause() {
        ReportFileException first = expectFailure(404, () -> provider.loadReport("file:missing-report.xml"));
        ReportFileException second = expectFailure(404, () -> provider.loadReport("file:another-missing.xml"));
        assertEquals(first.getMessage(), second.getMessage());
        assertFalse(first.getMessage().contains("missing-report.xml"));
        assertFalse(second.getMessage().contains("another-missing.xml"));
    }

    @Test
    public void unavailableStorageHasSanitizedServerError() {
        FileReportProvider unavailable = new FileReportProvider();
        unavailable.setFileStoreDir(temporaryFolder.getRoot().toPath().resolve("missing-storage").toString());
        ReportFileException error = expectFailure(500, unavailable::getReportFiles);
        assertFalse(error.getMessage().contains("missing-storage"));
    }

    @Test
    public void listsOnlyOrdinaryXmlReportsInTheStorageDirectory() throws IOException {
        write(reportRoot.resolve("monthly.xml"), ORIGINAL);
        write(reportRoot.resolve("年度 汇总.ureport.xml"), ORIGINAL);
        write(reportRoot.resolve("application.yml"), ORIGINAL);
        write(reportRoot.resolve("source.jar"), ORIGINAL);
        write(reportRoot.resolve("monthly.xml.bak"), ORIGINAL);
        Files.createDirectory(reportRoot.resolve("directory.xml"));
        Path subdirectory = Files.createDirectory(reportRoot.resolve("subdirectory"));
        write(subdirectory.resolve("nested.xml"), ORIGINAL);

        Set<String> names = new HashSet<String>();
        for (ReportFile report : provider.getReportFiles()) {
            names.add(report.getName());
            assertNotNull(report.getUpdateDate());
        }
        assertEquals(new HashSet<String>(Arrays.asList("monthly.xml", "年度 汇总.ureport.xml")), names);
    }

    @Test
    public void symbolicFilesCannotReadOverwriteOrDeleteOutsideTargets() throws IOException {
        Path link = reportRoot.resolve("outside-link.xml");
        createSymbolicLinkOrSkip(link, outsideFile);
        assertAllOperationsReject("file:outside-link.xml");
        assertTrue(Files.isSymbolicLink(link));
        assertOutsideFilesUnchanged();
        assertTrue(provider.getReportFiles().isEmpty());
    }

    @Test
    public void symbolicDirectoriesCannotReadOverwriteDeleteOrCreateOutsideTargets() throws IOException {
        Path link = reportRoot.resolve("linked-directory");
        createSymbolicLinkOrSkip(link, siblingRoot);
        assertAllOperationsReject("file:linked-directory/outside.xml");
        expectFailure(400, () -> provider.saveReport("file:linked-directory/new.xml", REPLACEMENT));
        assertTrue(Files.isSymbolicLink(link));
        assertFalse(Files.exists(siblingRoot.resolve("new.xml")));
        assertOutsideFilesUnchanged();
    }

    @Test
    public void symbolicFilesAreRejectedEvenWhenTargetIsInsideStorage() throws IOException {
        Path target = reportRoot.resolve("target.xml");
        write(target, ORIGINAL);
        Path link = reportRoot.resolve("inside-link.xml");
        createSymbolicLinkOrSkip(link, target);
        assertAllOperationsReject("file:inside-link.xml");
        assertEquals(ORIGINAL, read(target));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(1, provider.getReportFiles().size());
        assertEquals("target.xml", provider.getReportFiles().get(0).getName());
    }

    private void assertAllOperationsReject(String name) {
        ReportFileException readError = expectFailure(400, () -> provider.loadReport(name));
        ReportFileException saveError = expectFailure(400, () -> provider.saveReport(name, REPLACEMENT));
        ReportFileException deleteError = expectFailure(400, () -> provider.deleteReport(name));
        assertEquals(readError.getMessage(), saveError.getMessage());
        assertEquals(readError.getMessage(), deleteError.getMessage());
    }

    private ReportFileException expectFailure(int statusCode, Operation operation) {
        try {
            operation.run();
            fail("Expected report file operation to fail with HTTP status " + statusCode);
            return null;
        } catch (ReportFileException error) {
            assertEquals(statusCode, error.getStatusCode());
            assertNotNull(error.getMessage());
            assertFalse(error.getMessage().contains(temporaryFolder.getRoot().getAbsolutePath()));
            assertFalse(error.getMessage().contains(reportRoot.toString()));
            assertNull("File system exception causes must not reach response error handlers", error.getCause());
            return error;
        }
    }

    private String readReport(String name) throws IOException {
        try (InputStream input = provider.loadReport(name)) {
            return IOUtils.toString(input, StandardCharsets.UTF_8);
        }
    }

    private void assertOutsideFilesUnchanged() throws IOException {
        assertEquals(ORIGINAL, read(outsideFile));
        assertEquals(ORIGINAL, read(siblingFile));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            Assume.assumeNoException("Symbolic links are unavailable on this platform", error);
        }
    }

    private interface Operation {
        void run();
    }
}
