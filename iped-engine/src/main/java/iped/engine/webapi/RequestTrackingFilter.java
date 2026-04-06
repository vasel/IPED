package iped.engine.webapi;

import java.io.Closeable;
import java.io.IOException;

import javax.annotation.Priority;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;

import org.glassfish.grizzly.http.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter to track all HTTP requests for monitoring purposes.
 */
@Provider
@Priority(1)
public class RequestTrackingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestTrackingFilter.class);
    private static final long SLOW_REQUEST_WARN_MS = 2000;
    private static final int BODY_CAPTURE_LIMIT = Integer
            .parseInt(System.getProperty("iped.webapi.body.max", "8192"));

    private static final String REQUEST_ID_PROPERTY = "iped.request.id";

    @javax.ws.rs.core.Context
    private javax.inject.Provider<Request> grizzlyRequestProvider;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String method = requestContext.getMethod();
        String path = requestContext.getUriInfo().getPath();
        String queryString = requestContext.getUriInfo().getRequestUri().getRawQuery();

        long requestId = RequestTracker.getInstance().startRequest(method, path, queryString);
        requestContext.setProperty(REQUEST_ID_PROPERTY, requestId);
        RequestTracker.setCurrentRequestId(requestId);

        captureRequestBody(requestContext, requestId);
        captureClientIp(requestContext, null, requestId);

        if (LOGGER.isDebugEnabled()) {
            String fullPath = queryString != null && !queryString.isEmpty() ? path + "?" + queryString : path;
            LOGGER.debug("Started request id={} {} {}", requestId, method, fullPath);
        }

        // Store Grizzly connection for forced cancellation
        try {
            Request grizzlyRequest = grizzlyRequestProvider.get();
            attachConnection(requestId, grizzlyRequest);
            captureClientIp(requestContext, grizzlyRequest, requestId);
        } catch (Exception e) {
            // Grizzly request not available, skip connection/IP tracking
        }

        if (RequestTracker.getInstance().getRequest(requestId) != null
                && RequestTracker.getInstance().getRequest(requestId).getConnection() == null) {
            Object prop = requestContext.getProperty(Request.class.getName());
            if (prop instanceof Request) {
                attachConnection(requestId, (Request) prop);
            } else {
                Object altProp = requestContext.getProperty("org.glassfish.grizzly.http.server.Request");
                if (altProp instanceof Request) {
                    attachConnection(requestId, (Request) altProp);
                }
            }
        }
    }

    private void attachConnection(long requestId, Request grizzlyRequest) {
        if (grizzlyRequest == null) {
            return;
        }
        org.glassfish.grizzly.Connection<?> conn = grizzlyRequest.getContext().getConnection();
        if (conn == null) {
            return;
        }
        RequestTracker.RequestInfo info = RequestTracker.getInstance().getRequest(requestId);
        if (info != null) {
            info.setConnection(new Closeable() {
                @Override
                public void close() throws IOException {
                    conn.closeSilently();
                }
            });
        }
    }

    private void captureClientIp(ContainerRequestContext requestContext, Request grizzlyRequest, long requestId) {
        String ip = null;

        // Prefer forward headers (may contain comma-separated list)
        String xff = requestContext.getHeaderString("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            ip = xff.split(",")[0].trim();
        }

        if ((ip == null || ip.isEmpty()) && requestContext.getHeaderString("X-Real-IP") != null) {
            ip = requestContext.getHeaderString("X-Real-IP").trim();
        }

        if ((ip == null || ip.isEmpty()) && grizzlyRequest != null) {
            try {
                ip = grizzlyRequest.getRemoteAddr();
            } catch (Exception ignore) {
            }
        }

        if (ip == null || ip.isEmpty()) {
            return;
        }

        RequestTracker.RequestInfo info = RequestTracker.getInstance().getRequest(requestId);
        if (info != null) {
            info.setClientIp(ip);
        }
    }

    private void captureRequestBody(ContainerRequestContext requestContext, long requestId) {
        String method = requestContext.getMethod();
        if (!requestContext.hasEntity()) {
            return;
        }
        if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method))) {
            return;
        }
        try {
            java.io.InputStream in = requestContext.getEntityStream();
            java.io.ByteArrayOutputStream full = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int read;
            while ((read = in.read(tmp)) != -1) {
                full.write(tmp, 0, read);
            }
            byte[] allBytes = full.toByteArray();
            requestContext.setEntityStream(new java.io.ByteArrayInputStream(allBytes));

            int captureLen = Math.min(BODY_CAPTURE_LIMIT, allBytes.length);
            String body = new String(allBytes, 0, captureLen, java.nio.charset.StandardCharsets.UTF_8);
            boolean truncated = allBytes.length > BODY_CAPTURE_LIMIT;

            RequestTracker.RequestInfo info = RequestTracker.getInstance().getRequest(requestId);
            if (info != null) {
                info.setRequestBody(body, truncated);
            }
        } catch (Exception e) {
            // Do not block request on body capture issues
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) 
            throws IOException {
        Long requestId = (Long) requestContext.getProperty(REQUEST_ID_PROPERTY);
        if (requestId != null) {
            int status = responseContext.getStatus();
            RequestTracker tracker = RequestTracker.getInstance();
            if (status >= 400) {
                String error = "HTTP " + status;
                Object entity = responseContext.getEntity();
                if (entity != null) {
                    error += ": " + entity.toString();
                }
                tracker.failRequest(requestId, error);
            } else {
                tracker.completeRequest(requestId, status);
            }

            RequestTracker.RequestInfo info = tracker.getRequest(requestId);
            if (info != null) {
                long duration = info.getDurationMs();
                String fullPath = info.getFullPath();
                if (status >= 400) {
                    LOGGER.warn("Request id={} {} {} failed with HTTP {} after {} ms", requestId, info.getMethod(),
                            fullPath, status, duration);
                } else if (duration > SLOW_REQUEST_WARN_MS) {
                    LOGGER.warn("Slow request id={} {} {} took {} ms (HTTP {})", requestId, info.getMethod(),
                            fullPath, duration, status);
                } else if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Request id={} {} {} completed in {} ms (HTTP {})", requestId, info.getMethod(),
                            fullPath, duration, status);
                }
            }
        }
        RequestTracker.clearCurrentRequestId();
    }
}
