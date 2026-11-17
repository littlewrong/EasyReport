package cn.easyreport.utils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;

/** Servlet request fixture for exercising real URI parsing without a container. */
public final class HttpDatasetRequestFixture {
    private HttpDatasetRequestFixture() {
    }

    public static HttpServletRequest request(String scheme, String host, int port, String contextPath,
                                            Map<String, String> suppliedHeaders) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        suppliedHeaders.forEach((name, value) -> headers.put(name, Collections.singletonList(value)));
        Map<String, Object> attributes = new HashMap<>();
        String path = contextPath + "/ureport/designer";
        String authority = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        boolean standardPort = "https".equals(scheme) ? port == 443 : port == 80;
        String requestUrl = scheme + "://" + authority + (standardPort ? "" : ":" + port) + path;
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getScheme": return scheme;
                        case "isSecure": return "https".equals(scheme);
                        case "getServerName": return host;
                        case "getServerPort": return port;
                        case "getContextPath": return contextPath;
                        case "getRequestURI": return path;
                        case "getRequestURL": return new StringBuffer(requestUrl);
                        case "getMethod": return "GET";
                        case "getHeaderNames": return Collections.enumeration(headers.keySet());
                        case "getHeaders": return Collections.enumeration(
                                headers.getOrDefault((String) args[0], Collections.emptyList()));
                        case "getHeader": {
                            List<String> values = headers.get((String) args[0]);
                            return values == null ? null : values.get(0);
                        }
                        case "getAttribute": return attributes.get(args[0]);
                        case "getAttributeNames": return Collections.enumeration(new ArrayList<>(attributes.keySet()));
                        case "setAttribute": attributes.put((String) args[0], args[1]); return null;
                        case "removeAttribute": attributes.remove(args[0]); return null;
                        case "getParameterMap": return Collections.emptyMap();
                        case "toString": return requestUrl;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
    }

    public static HttpServletRequest request(String scheme, String host, int port, String contextPath) {
        return request(scheme, host, port, contextPath, Collections.emptyMap());
    }

    public static Map<String, String> headers(String... pairs) {
        Map<String, String> headers = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) headers.put(pairs[index], pairs[index + 1]);
        return headers;
    }
}
