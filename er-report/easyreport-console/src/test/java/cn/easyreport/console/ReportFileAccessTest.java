package cn.easyreport.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import cn.easyreport.console.designer.DesignerServletAction;
import cn.easyreport.console.html.HtmlPreviewServletAction;
import cn.easyreport.exception.ReportFileException;
import cn.easyreport.export.ExportManager;
import cn.easyreport.provider.report.ReportProvider;
import cn.easyreport.provider.report.file.FileReportProvider;

public class ReportFileAccessTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void servletRejectsTraversalAndDoesNotExposeServerPaths() throws Exception {
        Path base = temporaryFolder.newFolder("reports").toPath();
        Path outside = temporaryFolder.newFile("outside.xml").toPath();
        Files.write(outside, "outside-secret-marker".getBytes(StandardCharsets.UTF_8));
        DesignerServletAction action = designer(fileProvider(base));

        for (String method : Arrays.asList("loadReportFile", "downloadReportFile", "saveReportFile")) {
            for (String path : Arrays.asList("../outside.xml", "%2e%2e%2foutside.xml", "%252e%252e%252foutside.xml")) {
                Response response = invoke(action, method, "file:" + path);
                assertEquals(method + ": " + path, 400, response.status);
                assertEquals("Invalid report file path.", response.text());
                assertFalse(response.text().contains(base.toString()));
                assertFalse(response.text().contains("outside-secret-marker"));
                assertEquals("outside-secret-marker", new String(Files.readAllBytes(outside), StandardCharsets.UTF_8));
            }
        }
        assertEquals(400, invoke(action, "deleteReportFile", "file:../outside.xml").status);
        assertTrue(Files.exists(outside));
    }

    @Test
    public void missingReportReturns404WithoutItsAbsolutePath() throws Exception {
        Path base = temporaryFolder.newFolder("reports").toPath();
        Response response = invoke(designer(fileProvider(base)), "downloadReportFile", "file:missing.xml");
        assertEquals(404, response.status);
        assertEquals("Report file not found.", response.text());
        assertFalse(response.text().contains(base.toString()));
    }

    @Test
    public void unicodeNamesStillLoadDownloadAndDeleteUsingEachEndpointsEncoding() throws Exception {
        Path base = temporaryFolder.newFolder("reports").toPath();
        String fileName = "月度 报表.easyreport.xml";
        Path report = base.resolve(fileName);
        String content = "<report>中文内容</report>";
        Files.write(report, content.getBytes(StandardCharsets.UTF_8));
        String file = "file:" + URLEncoder.encode(URLEncoder.encode(fileName, "UTF-8"), "UTF-8");
        DesignerServletAction action = designer(fileProvider(base));

        Response loaded = invoke(action, "loadReportFile", file);
        assertEquals(200, loaded.status);
        assertEquals(content, loaded.text());
        Response downloaded = invoke(action, "downloadReportFile", file);
        assertEquals(200, downloaded.status);
        assertEquals(content, downloaded.text());
        assertEquals("application/octet-stream", downloaded.contentType);
        assertEquals(200, invoke(action, "deleteReportFile", "file:" + fileName).status);
        assertFalse(Files.exists(report));
    }

    @Test
    public void deletingNamesContainingPlusOrPercentDoesNotDecodeOrDeleteOtherFiles() throws Exception {
        Path base = temporaryFolder.newFolder("reports").toPath();
        DesignerServletAction action = designer(fileProvider(base));
        Path neighbor = base.resolve("A B.xml");
        Files.write(neighbor, "must-remain".getBytes(StandardCharsets.UTF_8));
        for (String fileName : Arrays.asList("A+B.xml", "A%20B.xml", "完成100%.xml")) {
            Path report = base.resolve(fileName);
            Files.write(report, "delete-only-this-file".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, invoke(action, "deleteReportFile", "file:" + fileName).status);
            assertFalse(Files.exists(report));
            assertEquals("must-remain", new String(Files.readAllBytes(neighbor), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void missingNamesReturnSafe400AcrossDesignerEndpoints() throws Exception {
        DesignerServletAction action = designer(fileProvider(temporaryFolder.newFolder("reports").toPath()));
        for (String method : Arrays.asList("loadReport", "loadReportFile", "downloadReportFile", "saveReportFile", "deleteReportFile")) {
            for (String file : Arrays.asList(null, "")) {
                Response response = invoke(action, method, file);
                assertEquals(method, 400, response.status);
                assertFalse(response.text().contains("Exception"));
            }
        }
    }

    @Test
    public void malformedEncodedNamesReturnSafe400ForEndpointsThatDecodeNames() throws Exception {
        DesignerServletAction action = designer(fileProvider(temporaryFolder.newFolder("reports").toPath()));
        for (String method : Arrays.asList("loadReport", "loadReportFile", "downloadReportFile", "saveReportFile")) {
            Response response = invoke(action, method, "file:%ZZ-private-name");
            assertEquals(method, 400, response.status);
            assertFalse(response.text().contains("private-name"));
            assertFalse(response.text().contains("Exception"));
        }
    }

    @Test
    public void htmlPreviewReturnsWrappedFileErrorWithoutRenderingAStackTrace() throws Exception {
        HtmlPreviewServletAction action = new HtmlPreviewServletAction();
        action.setExportManager(proxy(ExportManager.class, (object, method, arguments) -> {
            throw new RuntimeException("/private/server/report/path", new ReportFileException(400, "Invalid report file path."));
        }));
        Response response = new Response();
        action.execute(request("/easyreport/preview", Collections.singletonMap("_u", "file:../outside.xml")), response.proxy());
        assertEquals(400, response.status);
        assertEquals("text/plain", response.contentType);
        assertEquals("Invalid report file path.", response.text());
        assertFalse(response.text().contains("/private/server"));
        assertFalse(response.text().contains("Exception"));
    }

    @Test
    public void downloadClosesItsInputOnSuccessAndWhenResponseWriteFails() throws Exception {
        for (boolean failOutput : Arrays.asList(false, true)) {
            final boolean[] closed = { false };
            FileReportProvider provider = new FileReportProvider() {
                @Override
                public InputStream loadReport(String file) {
                    return new ByteArrayInputStream("report-data".getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public void close() throws IOException {
                            closed[0] = true;
                            super.close();
                        }
                    };
                }
            };
            Response response = new Response();
            response.failOutput = failOutput;
            try {
                designer(provider).downloadReportFile(request("/easyreport/designer/downloadReportFile", Collections.singletonMap("file", "file:report.xml")), response.proxy());
                if (failOutput) {
                    fail("Expected a response write failure");
                }
            } catch (IOException expected) {
                assertTrue(failOutput);
            }
            assertTrue("Download input must be closed", closed[0]);
        }
    }

    @Test
    public void fileErrorsReplaceUncommittedBinaryOutputButLeaveCommittedResponsesAlone() throws Exception {
        Response response = new Response();
        HttpServletResponse httpResponse = response.proxy();
        httpResponse.getOutputStream().write("discarded partial data".getBytes(StandardCharsets.UTF_8));
        assertTrue(ReportFileResponses.writeIfFileError(httpResponse, new ReportFileException(500, "Unable to access report file.")));
        assertEquals(500, response.status);
        assertEquals("Unable to access report file.", response.text());

        response.committed = true;
        assertTrue(ReportFileResponses.writeIfFileError(httpResponse, new ReportFileException(404, "Report file not found.")));
        assertEquals(500, response.status);
        assertEquals("Unable to access report file.", response.text());
    }

    private static FileReportProvider fileProvider(Path base) {
        FileReportProvider provider = new FileReportProvider();
        provider.setFileStoreDir(base.toString());
        return provider;
    }

    private static DesignerServletAction designer(ReportProvider provider) throws Exception {
        DesignerServletAction action = new DesignerServletAction();
        Field providers = DesignerServletAction.class.getDeclaredField("reportProviders");
        providers.setAccessible(true);
        providers.set(action, Collections.singletonList(provider));
        return action;
    }

    private static Response invoke(DesignerServletAction action, String method, String file) throws Exception {
        UReportServlet servlet = new UReportServlet();
        Field actions = UReportServlet.class.getDeclaredField("actionMap");
        actions.setAccessible(true);
        actions.set(servlet, Collections.singletonMap("/designer", action));
        Map<String, String> parameters = new HashMap<String, String>();
        if (file != null) {
            parameters.put("file", file);
        }
        parameters.put("content", "<report/> ");
        Response response = new Response();
        servlet.service(request("/easyreport/designer/" + method, parameters), response.proxy());
        assertNull("Request thread-local must be cleared even for file errors", RequestHolder.getRequest());
        return response;
    }

    private static HttpServletRequest request(final String uri, final Map<String, String> parameters) {
        return proxy(HttpServletRequest.class, (object, method, arguments) -> {
            switch (method.getName()) {
                case "getContextPath": return "";
                case "getRequestURI": return uri;
                case "getParameter": return parameters.get(arguments[0]);
                case "getParameterNames": return Collections.enumeration(parameters.keySet());
                default: return defaultValue(method.getReturnType());
            }
        });
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

    private static final class Response implements InvocationHandler {
        private int status = 200;
        private String contentType;
        private boolean committed;
        private boolean failOutput;
        private boolean outputSelected;
        private boolean writerSelected;
        private final StringWriter text = new StringWriter();
        private final PrintWriter writer = new PrintWriter(text);
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final ServletOutputStream output = new ServletOutputStream() {
            @Override
            public void write(int value) throws IOException {
                if (failOutput) throw new IOException("Simulated client disconnect");
                bytes.write(value);
            }
            @Override
            public boolean isReady() { return true; }
            @Override
            public void setWriteListener(WriteListener listener) { }
        };

        private HttpServletResponse proxy() {
            return ReportFileAccessTest.proxy(HttpServletResponse.class, this);
        }

        private String text() {
            writer.flush();
            return outputSelected ? new String(bytes.toByteArray(), StandardCharsets.UTF_8) : text.toString();
        }

        @Override
        public Object invoke(Object object, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "isCommitted": return committed;
                case "setStatus": status = (Integer) arguments[0]; return null;
                case "setContentType": contentType = (String) arguments[0]; return null;
                case "resetBuffer": text.getBuffer().setLength(0); bytes.reset(); return null;
                case "getWriter":
                    if (outputSelected) throw new IllegalStateException("Output stream selected");
                    writerSelected = true;
                    return writer;
                case "getOutputStream":
                    if (writerSelected) throw new IllegalStateException("Writer selected");
                    outputSelected = true;
                    return output;
                default: return defaultValue(method.getReturnType());
            }
        }
    }
}
