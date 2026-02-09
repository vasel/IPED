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

/**
 * Filter to track all HTTP requests for monitoring purposes.
 */
@Provider
@Priority(1)
public class RequestTrackingFilter implements ContainerRequestFilter, ContainerResponseFilter {

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

        // Store Grizzly connection for forced cancellation
        try {
            Request grizzlyRequest = grizzlyRequestProvider.get();
            attachConnection(requestId, grizzlyRequest);
        } catch (Exception e) {
            // Grizzly request not available, skip connection tracking
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

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) 
            throws IOException {
        Long requestId = (Long) requestContext.getProperty(REQUEST_ID_PROPERTY);
        if (requestId != null) {
            int status = responseContext.getStatus();
            if (status >= 400) {
                String error = "HTTP " + status;
                Object entity = responseContext.getEntity();
                if (entity != null) {
                    error += ": " + entity.toString();
                }
                RequestTracker.getInstance().failRequest(requestId, error);
            } else {
                RequestTracker.getInstance().completeRequest(requestId, status);
            }
        }
    }
}
