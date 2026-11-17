package cn.easyreport.console.designer;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.easyreport.definition.dataset.HttpDatasetDefinition;

public class DatasourceHttpUrlResolutionTest {
    private RequestAttributes previousRequest;
    private String previousBase;

    @Before
    public void keepAConfiguredBackgroundBaseToDetectInteractiveOverrides() {
        previousRequest = RequestContextHolder.getRequestAttributes();
        previousBase = System.getProperty("easyreport.http.base-url");
        System.setProperty("easyreport.http.base-url", "https://fixed-background.invalid");
    }

    @After
    public void restoreContext() {
        if (previousRequest == null) RequestContextHolder.resetRequestAttributes();
        else RequestContextHolder.setRequestAttributes(previousRequest);
        if (previousBase == null) System.clearProperty("easyreport.http.base-url");
        else System.setProperty("easyreport.http.base-url", previousBase);
    }

    @Test
    public void designerAndRuntimeUseTheSameExternalOriginForEveryProtocolMode() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Forwarded-Proto", "https");
        headers.put("X-Forwarded-Host", "browser.example");
        headers.put("X-Forwarded-Port", "443");
        HttpServletRequest reportRequest = request("http", "internal-service", 8080, headers);
        for (String mode : new String[]{"auto", "http", "https"}) {
            String expected = ("http".equals(mode) ? "http" : "https") + "://browser.example/executorapi/run";
            assertEquals(mode, expected, designerUrl(reportRequest, "/executorapi/run", mode));
            assertEquals(mode, expected, runtimeUrl(reportRequest, "/executorapi/run", mode));
        }
    }

    @Test
    public void bothPathsFollowEachNewReportDomainAndPreserveItsPort() throws Exception {
        HttpServletRequest first = request("https", "first.example", 8443, Collections.emptyMap());
        HttpServletRequest second = request("http", "second.example", 8081, Collections.emptyMap());
        assertEquals("https://first.example:8443/data", designerUrl(first, "/data", "auto"));
        assertEquals("https://first.example:8443/data", runtimeUrl(first, "/data", "auto"));
        assertEquals("http://second.example:8081/data", designerUrl(second, "/data", "auto"));
        assertEquals("http://second.example:8081/data", runtimeUrl(second, "/data", "auto"));
    }

    @Test
    public void explicitApiUrlsArePreservedInDesignerAndRuntime() throws Exception {
        HttpServletRequest report = request("http", "report.example", 80, Collections.emptyMap());
        String apiUrl = "https://external-api.example:9443/run";
        assertEquals(apiUrl, designerUrl(report, apiUrl, "http"));
        assertEquals(apiUrl, runtimeUrl(report, apiUrl, "http"));
    }

    private String designerUrl(HttpServletRequest request, String url, String protocol) throws Exception {
        Method resolver = DatasourceServletAction.class.getDeclaredMethod(
                "resolveUrl", HttpServletRequest.class, String.class, String.class);
        resolver.setAccessible(true);
        return (String) resolver.invoke(new DatasourceServletAction(), request, url, protocol);
    }

    private String runtimeUrl(HttpServletRequest request, String url, String protocol) throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        HttpDatasetDefinition dataset = new HttpDatasetDefinition();
        dataset.setProtocolForce(protocol);
        Method resolver = HttpDatasetDefinition.class.getDeclaredMethod("resolveUrl", String.class);
        resolver.setAccessible(true);
        return (String) resolver.invoke(dataset, url);
    }

    private HttpServletRequest request(String scheme, String host, int port, Map<String, String> headers) {
        String requestUrl = scheme + "://" + host
                + ((("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80))
                        ? "" : ":" + port) + "/ureport/designer";
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getScheme": return scheme;
                        case "getServerName": return host;
                        case "getServerPort": return port;
                        case "getContextPath": return "";
                        case "getRequestURI": return "/ureport/designer";
                        case "getRequestURL": return new StringBuffer(requestUrl);
                        case "getMethod": return "GET";
                        case "getHeaderNames": return Collections.enumeration(headers.keySet());
                        case "getHeader": return headers.get(args[0]);
                        case "getHeaders": return Collections.enumeration(headers.containsKey(args[0])
                                ? Collections.singletonList(headers.get(args[0])) : Collections.emptyList());
                        case "toString": return requestUrl;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
    }
}
