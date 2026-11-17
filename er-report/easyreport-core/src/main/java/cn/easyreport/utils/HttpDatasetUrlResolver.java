package cn.easyreport.utils;

import java.util.Locale;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/** Resolves HTTP dataset paths using the origin of the current report request. */
public final class HttpDatasetUrlResolver {
    private static final ThreadLocal<RequestOrigin> REQUEST_ORIGIN = new ThreadLocal<RequestOrigin>();

    private HttpDatasetUrlResolver() {
    }

    /** Immutable address snapshot; it never retains a servlet request after completion. */
    public static final class RequestOrigin {
        private final UriComponents address;
        private final String contextPath;

        private RequestOrigin(UriComponents address, String contextPath) {
            this.address = address;
            this.contextPath = contextPath == null ? "" : contextPath;
        }
    }

    /** Restores the previous origin even when an asynchronous export fails. */
    public static final class OriginScope implements AutoCloseable {
        private final RequestOrigin previous;
        private boolean closed;

        private OriginScope(RequestOrigin previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            if (previous == null) REQUEST_ORIGIN.remove();
            else REQUEST_ORIGIN.set(previous);
            closed = true;
        }
    }

    public static RequestOrigin captureOrigin(HttpServletRequest request) {
        if (request == null) throw new IllegalArgumentException("HTTP request is required to capture its origin.");
        // A host application may already have wrapped the request. Spring also
        // restores Forwarded / X-Forwarded-* origins. The edge proxy must replace
        // untrusted forwarding headers before passing requests to the application.
        UriComponents address = UriComponentsBuilder.fromHttpRequest(new ServletServerHttpRequest(request))
                .replacePath(null).replaceQuery(null).fragment(null).userInfo(null).build();
        return new RequestOrigin(address, request.getContextPath());
    }

    public static OriginScope withOrigin(RequestOrigin origin) {
        OriginScope scope = new OriginScope(REQUEST_ORIGIN.get());
        if (origin == null) REQUEST_ORIGIN.remove();
        else REQUEST_ORIGIN.set(origin);
        return scope;
    }

    public static String normalizeProtocol(String protocol) {
        String value = protocol == null ? "auto" : protocol.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) value = "auto";
        if (!"auto".equals(value) && !"http".equals(value) && !"https".equals(value)) {
            throw new IllegalArgumentException("HTTP dataset protocol must be auto, http or https.");
        }
        return value;
    }

    public static String resolve(HttpServletRequest request, String rawUrl, String protocolForce) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("HTTP dataset url can not be empty.");
        }
        String url = rawUrl.trim();
        String protocol = normalizeProtocol(protocolForce);
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) return url;

        RequestOrigin snapshot = request == null ? REQUEST_ORIGIN.get() : captureOrigin(request);
        if (snapshot != null) {
            UriComponents origin = snapshot.address;
            String scheme = origin.getScheme();
            int port = origin.getPort();
            if (!"auto".equals(protocol)) {
                scheme = protocol;
                if (("https".equals(scheme) && port == 80) || ("http".equals(scheme) && port == 443)) {
                    port = -1;
                }
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("HTTP dataset requires an HTTP or HTTPS request origin.");
            }
            if (("https".equalsIgnoreCase(scheme) && port == 443)
                    || ("http".equalsIgnoreCase(scheme) && port == 80)) port = -1;
            String base = UriComponentsBuilder.newInstance().scheme(scheme).host(origin.getHost())
                    .port(port).build().toUriString();
            String path = url.startsWith("/") ? url
                    : snapshot.contextPath + "/" + url;
            return base + path;
        }

        // Background exports have no browser request. Keep the configured base as
        // a fallback for that case, without overriding interactive report origins.
        String base = System.getProperty("easyreport.http.base-url");
        if (base == null || base.trim().isEmpty()) base = System.getenv("EASYREPORT_HTTP_BASE_URL");
        if (base != null && !base.trim().isEmpty()) {
            base = base.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base + (url.startsWith("/") ? url : "/" + url);
        }
        throw new IllegalArgumentException("Relative HTTP dataset url [" + url
                + "] can not be resolved. Configure easyreport.http.base-url or invoke within an HTTP request.");
    }
}
