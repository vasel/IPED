package iped.engine.webapi;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Priority;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.WriterInterceptor;
import javax.ws.rs.ext.WriterInterceptorContext;

/**
 * Applies GZIP compression only to text-based responses (JSON, HTML, XML, plain text)
 * when the client signals support via Accept-Encoding: gzip.
 * Binary responses (octet-stream, images, etc.) are sent uncompressed so that
 * the browser can start downloading immediately without waiting for the server
 * to finish compressing the whole payload.
 */
@Provider
@Priority(javax.ws.rs.Priorities.ENTITY_CODER)
public class SelectiveGzipInterceptor implements WriterInterceptor {

    @Context
    private HttpHeaders httpHeaders;

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        MultivaluedMap<String, Object> headers = context.getHeaders();
        MediaType mediaType = context.getMediaType();

        if (clientAcceptsGzip() && shouldCompress(mediaType)) {
            headers.putSingle(HttpHeaders.CONTENT_ENCODING, "gzip");
            // Remove Content-Length since compressed size is unknown
            headers.remove(HttpHeaders.CONTENT_LENGTH);
            OutputStream original = context.getOutputStream();
            GZIPOutputStream gzipOut = new GZIPOutputStream(original, 8192);
            context.setOutputStream(gzipOut);
            try {
                context.proceed();
            } finally {
                try {
                    gzipOut.finish();
                } catch (IOException suppressed) {
                    // Connection already closed by the client — nothing to flush.
                }
            }
            return;
        }

        // No compression for binary content
        context.proceed();
    }

    private boolean clientAcceptsGzip() {
        if (httpHeaders == null) return false;
        java.util.List<String> accept = httpHeaders.getRequestHeader("Accept-Encoding");
        if (accept == null) return false;
        for (String val : accept) {
            if (val != null && val.toLowerCase().contains("gzip")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldCompress(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }

        String type = mediaType.getType();
        String subtype = mediaType.getSubtype();

        // Compress JSON
        if ("application".equals(type) && "json".equals(subtype)) {
            return true;
        }

        // Compress text/* (html, plain, xml, etc.)
        if ("text".equals(type)) {
            return true;
        }

        // Compress XML
        if ("application".equals(type) && (subtype.endsWith("+xml") || "xml".equals(subtype))) {
            return true;
        }

        return false;
    }
}
