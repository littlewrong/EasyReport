package cn.easyreport.utils;

import static cn.easyreport.utils.HttpDatasetRequestFixture.headers;
import static cn.easyreport.utils.HttpDatasetRequestFixture.request;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpDatasetUrlResolverTest {
    private static final String BASE_PROPERTY = "easyreport.http.base-url";
    private String originalBase;

    @Before
    public void keepBackgroundFallbackSeparateFromInteractiveOrigins() {
        originalBase = System.getProperty(BASE_PROPERTY);
        System.setProperty(BASE_PROPERTY, "http://fallback.invalid/reports/");
    }

    @After
    public void restoreConfiguredBase() {
        if (originalBase == null) System.clearProperty(BASE_PROPERTY);
        else System.setProperty(BASE_PROPERTY, originalBase);
    }

    @Test
    public void autoUsesCurrentHttpOrHttpsOriginInsteadOfFixedBase() {
        assertEquals("https://reports.example/executorapi/run", resolve(
                request("https", "reports.example", 443, ""), "/executorapi/run", "auto"));
        assertEquals("http://other.example/executorapi/run", resolve(
                request("http", "other.example", 80, ""), "/executorapi/run", "auto"));
    }

    @Test
    public void autoPreservesNonstandardPortsAndDoesNotCacheThePreviousDomain() {
        assertEquals("https://first.example:8443/data", resolve(
                request("https", "first.example", 8443, ""), "/data", "auto"));
        assertEquals("http://second.example:8081/data", resolve(
                request("http", "second.example", 8081, ""), "/data", "auto"));
        assertEquals("https://first.example:8443/data", resolve(
                request("https", "first.example", 8443, ""), "/data", "auto"));
    }

    @Test
    public void proxyHeadersRestoreExternalHttpsHostAndPort() {
        HttpServletRequest proxy = request("http", "internal-service", 8080, "",
                headers("X-Forwarded-Proto", "https", "X-Forwarded-Host", "reports.example",
                        "X-Forwarded-Port", "443"));
        assertEquals("https://reports.example/executorapi/run", resolve(proxy, "/executorapi/run", "auto"));
    }

    @Test
    public void proxyHeadersPreserveAnExternalNonstandardPort() {
        HttpServletRequest proxy = request("http", "internal-service", 8080, "",
                headers("X-Forwarded-Proto", "https", "X-Forwarded-Host", "reports.example:9443",
                        "X-Forwarded-Port", "9443"));
        assertEquals("https://reports.example:9443/data", resolve(proxy, "/data", "auto"));
    }

    @Test
    public void standardForwardedHeaderUsesOriginalQuotedHostAndProtocol() {
        HttpServletRequest proxy = request("http", "internal-service", 8080, "",
                headers("Forwarded", "for=192.0.2.43;proto=https;host=\"reports.example:8443\""));
        assertEquals("https://reports.example:8443/data", resolve(proxy, "/data", "auto"));
    }

    @Test
    public void forwardedChainsUseTheClientFacingOrigin() {
        HttpServletRequest proxy = request("http", "internal-service", 8080, "",
                headers("X-Forwarded-Proto", "https, http", "X-Forwarded-Host", "public.example, edge.internal",
                        "X-Forwarded-Port", "443, 8080"));
        assertEquals("https://public.example/data", resolve(proxy, "/data", "auto"));
    }

    @Test
    public void forcedProtocolChangesOnlyTheSchemeAndItsDefaultPort() {
        assertEquals("https://reports.example/data", resolve(
                request("http", "reports.example", 80, ""), "/data", "https"));
        assertEquals("http://reports.example/data", resolve(
                request("https", "reports.example", 443, ""), "/data", "http"));
        assertEquals("https://reports.example:8081/data", resolve(
                request("http", "reports.example", 8081, ""), "/data", "https"));
    }

    @Test
    public void contextRelativeAndRootRelativePathsKeepTheirDifferentSemantics() {
        HttpServletRequest report = request("https", "reports.example", 443, "/erp");
        assertEquals("https://reports.example/erp/executorapi/run?page=1&size=20", resolve(
                report, "executorapi/run?page=1&size=20", "auto"));
        assertEquals("https://reports.example/executorapi/run", resolve(report, "/executorapi/run", "auto"));
        assertEquals("https://reports.example/executorapi/run", resolve(
                request("https", "reports.example", 443, ""), "executorapi/run", "auto"));
    }

    @Test
    public void absoluteUrlsRemainExplicitRegardlessOfTheSelectedMode() {
        for (String mode : new String[]{"auto", "http", "https"}) {
            assertEquals("https://api.example:9443/data?a=1", resolve(
                    request("http", "report.example", 80, ""), " https://api.example:9443/data?a=1 ", mode));
            assertEquals("http://api.example/data", resolve(null, "http://api.example/data", mode));
        }
    }

    @Test
    public void missingOrCaseVariantProtocolValuesAreNormalized() {
        HttpServletRequest report = request("http", "reports.example", 80, "");
        for (String mode : new String[]{null, "", "  ", "AUTO", " auto "}) {
            assertEquals("http://reports.example/data", resolve(report, "/data", mode));
        }
        assertEquals("https://reports.example/data", resolve(report, "/data", " HTTPS "));
    }

    @Test
    public void invalidModesAndEmptyUrlsFailBeforeAnHttpRequestCanBeMade() {
        for (String mode : new String[]{"ftp", "javascript", "https://"}) {
            try {
                resolve(request("http", "reports.example", 80, ""), "/data", mode);
                fail("Accepted unsupported protocol: " + mode);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("protocol"));
            }
        }
        for (String url : new String[]{null, "", "  "}) {
            try {
                resolve(request("http", "reports.example", 80, ""), url, "auto");
                fail("Accepted an empty dataset URL");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("url"));
            }
        }
    }

    @Test
    public void backgroundCallsStillUseTheConfiguredBaseWithoutARequest() {
        assertEquals("http://fallback.invalid/reports/data", resolve(null, "/data", "auto"));
        assertEquals("http://fallback.invalid/reports/data", resolve(null, "data", "auto"));
    }

    @Test
    public void capturedOriginsAreScopedAndNestedScopesRestoreTheOuterReport() throws Exception {
        try (AutoCloseable outer = HttpDatasetUrlResolver.withOrigin(HttpDatasetUrlResolver.captureOrigin(
                request("https", "first.example", 443, "/first")))) {
            assertEquals("https://first.example/first/data", resolve(null, "data", "auto"));
            try (AutoCloseable inner = HttpDatasetUrlResolver.withOrigin(HttpDatasetUrlResolver.captureOrigin(
                    request("http", "second.example", 8082, "/second")))) {
                assertEquals("http://second.example:8082/second/data", resolve(null, "data", "auto"));
            }
            assertEquals("https://first.example/first/data", resolve(null, "data", "auto"));
            assertEquals("http://current.example/data", resolve(
                    request("http", "current.example", 80, ""), "/data", "auto"));
        }
        assertEquals("http://fallback.invalid/reports/data", resolve(null, "data", "auto"));
    }

    @Test
    public void exportWorkerOriginsDoNotLeakBetweenThreadsOrAfterClosing() throws Exception {
        AtomicReference<String> workerUrl = new AtomicReference<>();
        AtomicReference<String> workerAfterClose = new AtomicReference<>();
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        try (AutoCloseable parent = HttpDatasetUrlResolver.withOrigin(HttpDatasetUrlResolver.captureOrigin(
                request("https", "parent.example", 443, "")))) {
            Thread worker = new Thread(() -> {
                try (AutoCloseable ignored = HttpDatasetUrlResolver.withOrigin(HttpDatasetUrlResolver.captureOrigin(
                        request("http", "worker.example", 8082, "")))) {
                    workerUrl.set(resolve(null, "/data", "auto"));
                } catch (Throwable error) {
                    workerError.set(error);
                }
                workerAfterClose.set(resolve(null, "/data", "auto"));
            });
            worker.start();
            worker.join(5000);
            assertTrue("Worker did not finish", !worker.isAlive());
            if (workerError.get() != null) throw new AssertionError(workerError.get());
            assertEquals("http://worker.example:8082/data", workerUrl.get());
            assertEquals("http://fallback.invalid/reports/data", workerAfterClose.get());
            assertEquals("https://parent.example/data", resolve(null, "/data", "auto"));
        }
        assertEquals("http://fallback.invalid/reports/data", resolve(null, "/data", "auto"));
    }

    private String resolve(HttpServletRequest request, String url, String mode) {
        return HttpDatasetUrlResolver.resolve(request, url, mode);
    }
}
