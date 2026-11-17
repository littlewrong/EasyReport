package cn.easyreport.definition.dataset;

import static cn.easyreport.utils.HttpDatasetRequestFixture.request;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.dom4j.DocumentHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.easyreport.build.Dataset;
import cn.easyreport.definition.datasource.DatasourceDefinition;
import cn.easyreport.parser.impl.DatasourceParser;

public class HttpDatasetProtocolTest {
    private RequestAttributes previousRequest;
    private String previousBase;

    @Before
    public void saveRequestContextAndBase() {
        previousRequest = RequestContextHolder.getRequestAttributes();
        previousBase = System.getProperty("easyreport.http.base-url");
        System.setProperty("easyreport.http.base-url", "https://fixed-config.invalid");
    }

    @After
    public void restoreRequestContextAndBase() {
        HttpDatasetDefinition.clearTotalCount();
        if (previousRequest == null) RequestContextHolder.resetRequestAttributes();
        else RequestContextHolder.setRequestAttributes(previousRequest);
        if (previousBase == null) System.clearProperty("easyreport.http.base-url");
        else System.setProperty("easyreport.http.base-url", previousBase);
    }

    @Test
    public void explicitProtocolModesSurviveReloadFromReportXml() throws Exception {
        for (String mode : new String[]{"auto", "http", "https"}) {
            HttpDatasetDefinition dataset = parse("protocolForce='" + mode + "'");
            assertEquals(mode, dataset.getProtocolForce());
            assertEquals("/executorapi/run", dataset.getUrl());
            assertEquals("POST", dataset.getMethod());
            assertEquals("data.rows", dataset.getDataPath());
            assertEquals("amount", dataset.getFields().get(0).getName());
        }
    }

    @Test
    public void newAndLegacyDefinitionsDefaultToAutoInsteadOfForcingHttps() throws Exception {
        assertEquals("auto", new HttpDatasetDefinition().getProtocolForce());
        assertEquals("auto", parse("").getProtocolForce());
        assertEquals("auto", parse("protocolForce='' ").getProtocolForce());
        assertEquals("auto", parse("protocolForce=' AUTO '").getProtocolForce());
        assertEquals("https", parse("protocolForce=' HTTPS '").getProtocolForce());
    }

    @Test
    public void invalidSavedProtocolIsRejectedRatherThanUsedAsAnArbitraryScheme() throws Exception {
        try {
            parse("protocolForce='ftp'");
            fail("An unsupported protocol was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("protocol"));
        }
    }

    @Test
    public void aReloadedAutoDatasetActuallyCallsTheOriginOfEachReportRequest() throws Exception {
        HttpServer first = server("first");
        HttpServer second = server("second");
        try {
            HttpDatasetDefinition dataset = parse("protocolForce='auto'");
            assertDataFrom(dataset, first.getAddress().getPort(), "first");
            assertDataFrom(dataset, second.getAddress().getPort(), "second");
            assertDataFrom(dataset, first.getAddress().getPort(), "first");
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }

    private void assertDataFrom(HttpDatasetDefinition dataset, int port, String expectedValue) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                request("http", "127.0.0.1", port, "")));
        Dataset result = dataset.buildDataset(Collections.emptyMap());
        assertEquals(1, result.getData().size());
        Map<?, ?> row = (Map<?, ?>) result.getData().get(0);
        assertEquals(expectedValue, row.get("amount"));
    }

    private HttpServer server(String value) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/executorapi/run", exchange -> {
            try {
                while (exchange.getRequestBody().read() != -1) { }
                byte[] body = ("{\"data\":{\"rows\":[{\"amount\":\"" + value
                        + "\"}],\"total_count\":1}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private HttpDatasetDefinition parse(String protocolAttribute) throws Exception {
        String xml = "<datasource name='api' type='http'><dataset name='test' type='http' "
                + "url='/executorapi/run' method='POST' dataPath='data.rows' " + protocolAttribute + ">"
                + "<field name='amount'/></dataset></datasource>";
        DatasourceDefinition datasource = new DatasourceParser().parse(DocumentHelper.parseText(xml).getRootElement());
        return (HttpDatasetDefinition) datasource.getDatasets().get(0);
    }
}
