package cn.easyreport.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Test;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.easyreport.definition.dataset.HttpDatasetDefinition;

/** The console servlet must expose an origin even without a host MVC request filter. */
public class UReportRequestContextTest {
    @After
    public void clearContext() {
        RequestContextHolder.resetRequestAttributes();
        RequestHolder.clean();
    }

    @Test
    public void standaloneServletBindsRequestForDatasetAndCompletesItsScope() throws Exception {
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<String> resolved = new AtomicReference<String>();
        final HttpServletRequest request = request("http", "first.example", 8080);
        UReportServlet servlet = servlet(new ServletAction() {
            @Override
            public String url() { return "/preview"; }
            @Override
            public void execute(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                assertSame(request, attributes.getRequest());
                attributes.registerDestructionCallback("scope", () -> completed.set(true), RequestAttributes.SCOPE_REQUEST);
                resolved.set(resolveDatasetUrl());
            }
        });

        servlet.service(request, response());

        assertEquals("http://first.example:8080/executorapi/test", resolved.get());
        assertTrue(completed.get());
        assertNull(RequestContextHolder.getRequestAttributes());
        assertNull(RequestHolder.getRequest());
    }

    @Test
    public void restoresHostContextAfterSuccessAndFailureWithoutCompletingHostScope() throws Exception {
        for (final boolean failure : new boolean[] { false, true }) {
            final AtomicBoolean hostCompleted = new AtomicBoolean();
            final AtomicBoolean reportCompleted = new AtomicBoolean();
            final ServletRequestAttributes hostAttributes = new ServletRequestAttributes(request("http", "host.example", 80));
            hostAttributes.registerDestructionCallback("host", () -> hostCompleted.set(true), RequestAttributes.SCOPE_REQUEST);
            RequestContextHolder.setRequestAttributes(hostAttributes);
            UReportServlet servlet = servlet(new ServletAction() {
                @Override
                public String url() { return "/preview"; }
                @Override
                public void execute(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    ServletRequestAttributes reportAttributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                    assertSame(req, reportAttributes.getRequest());
                    reportAttributes.registerDestructionCallback("report", () -> reportCompleted.set(true), RequestAttributes.SCOPE_REQUEST);
                    if (failure) throw new IOException("Expected report failure");
                }
            });
            try {
                servlet.service(request("https", "report.example", 443), response());
                if (failure) fail("Expected failed report to propagate its exception");
            } catch (ServletException expected) {
                assertTrue(failure);
            }
            assertSame(hostAttributes, RequestContextHolder.getRequestAttributes());
            assertTrue(reportCompleted.get());
            assertFalse(hostCompleted.get());
            assertNull(RequestHolder.getRequest());
            hostAttributes.requestCompleted();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    public void sequentialDomainsDoNotShareThePreviousRequestOrigin() throws Exception {
        final AtomicReference<String> resolved = new AtomicReference<String>();
        UReportServlet servlet = servlet(new ServletAction() {
            @Override
            public String url() { return "/preview"; }
            @Override
            public void execute(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
                resolved.set(resolveDatasetUrl());
            }
        });
        servlet.service(request("https", "tenant-a.example", 443), response());
        assertEquals("https://tenant-a.example/executorapi/test", resolved.get());
        assertNull(RequestContextHolder.getRequestAttributes());

        servlet.service(request("http", "tenant-b.example", 80), response());
        assertEquals("http://tenant-b.example/executorapi/test", resolved.get());
        assertNull(RequestContextHolder.getRequestAttributes());
    }

    private static String resolveDatasetUrl() throws ServletException {
        try {
            Method resolve = HttpDatasetDefinition.class.getDeclaredMethod("resolveUrl", String.class);
            resolve.setAccessible(true);
            return (String) resolve.invoke(new HttpDatasetDefinition(), "/executorapi/test");
        } catch (Exception ex) {
            throw new ServletException(ex);
        }
    }

    private static UReportServlet servlet(ServletAction action) throws Exception {
        UReportServlet servlet = new UReportServlet();
        Field actions = UReportServlet.class.getDeclaredField("actionMap");
        actions.setAccessible(true);
        actions.set(servlet, Collections.singletonMap(action.url(), action));
        return servlet;
    }

    private static HttpServletRequest request(final String scheme, final String host, final int port) {
        return proxy(HttpServletRequest.class, (object, method, arguments) -> {
            switch (method.getName()) {
                case "getScheme": return scheme;
                case "getServerName": return host;
                case "getServerPort": return port;
                case "getContextPath": return "";
                case "getRequestURI": return "/easyreport/preview";
                case "getRequestURL": return new StringBuffer(scheme + "://" + host + ":" + port + "/easyreport/preview");
                case "getHeaderNames": return Collections.emptyEnumeration();
                case "getHeaders": return Collections.emptyEnumeration();
                default: return defaultValue(method.getReturnType());
            }
        });
    }

    private static HttpServletResponse response() {
        final PrintWriter writer = new PrintWriter(new StringWriter());
        return proxy(HttpServletResponse.class, (object, method, arguments) -> {
            if ("getWriter".equals(method.getName())) return writer;
            return defaultValue(method.getReturnType());
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
}
