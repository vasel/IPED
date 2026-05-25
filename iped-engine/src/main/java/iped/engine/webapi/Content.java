package iped.engine.webapi;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.sleuthkit.datamodel.TskCoreException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.engine.task.index.IndexItem;

@Tag(name = "Documents")
@Path("sources/{sourceID}/docs/{id}/content")
public class Content {

    private static final Logger LOGGER = Logger.getLogger(Content.class.getName());

    /** Buffer size for streaming content (64 KB). */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * How many buffer writes before an explicit flush to the client.
     * Each write is 64 KB, so flushing every 4 writes ≈ 256 KB granularity,
     * which gives the browser frequent progress-event callbacks without
     * adding excessive syscall overhead.
     */
    private static final int FLUSH_INTERVAL = 4;

    @Operation(summary = "Get document's raw content")
    @GET
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response content(@PathParam("sourceID") String sourceID, @PathParam("id") int id,
            @HeaderParam("Range") String rangeHeader)
            throws TskCoreException, IOException, URISyntaxException {

        // Phase tracking for request instrumentation
        RequestTracker.RequestInfo reqInfo = null;
        Long reqId = RequestTracker.getCurrentRequestId();
        if (reqId != null) {
            reqInfo = RequestTracker.getInstance().getRequest(reqId);
        }

        if (reqInfo != null) reqInfo.markPhase("resolve_source");
        IIPEDSource source = Sources.getSource(sourceID);

        // Use lightweight loader: loads only ~15 fields instead of 50+
        // Skips content, thumbnail, imageFeatures, and all metadata fields
        if (reqInfo != null) reqInfo.markPhase("load_item");
        int luceneId = source.getLuceneId(id);
        final IItem item = IndexItem.getItemForStreaming((IPEDSource) source, luceneId);
        final Long lengthObj = item.getLength();          // nullable
        final long totalLength = lengthObj != null ? lengthObj : -1L;
        final String fileName = item.getName();

        // Eagerly open the stream before committing the HTTP response.
        // If Sleuthkit (or another backend) fails here (e.g. "Seek to X failed"),
        // we can still return a proper HTTP error instead of a broken pipe.
        if (reqInfo != null) reqInfo.markPhase("open_stream");
        final InputStream eagleStream;
        try {
            eagleStream = item.getBufferedInputStream();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error opening content stream for item " + id + " (" + fileName + ")", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("Error reading content: " + e.getMessage())
                    .build();
        }

        if (reqInfo != null) reqInfo.markPhase("build_response");

        // Parse optional Range header (only single byte-range supported)
        if (totalLength > 0 && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            long[] range = parseRange(rangeHeader, totalLength);
            if (range != null) {
                final long start = range[0];
                final long end = range[1];
                final long contentLength = end - start + 1;
                return Response.status(206)
                        .header("Content-Length", String.valueOf(contentLength))
                        .header("Content-Range", "bytes " + start + "-" + end + "/" + totalLength)
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                        .entity(new StreamingOutput() {
                            @Override
                            public void write(OutputStream out) throws IOException, WebApplicationException {
                                try (InputStream is = eagleStream) {
                                    skipFully(is, start);
                                    copyBytes(is, out, contentLength);
                                }
                            }
                        }).build();
            }
        }

        // Full content response
        Response.ResponseBuilder rb = Response.ok()
                .header("Accept-Ranges", "bytes")
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

        // Content-Length is required for browser download-progress tracking.
        // Without it, Grizzly falls back to chunked transfer encoding and
        // the browser cannot compute percentage progress.
        if (totalLength >= 0) {
            rb.header("Content-Length", String.valueOf(totalLength));
        }

        return rb.entity(new StreamingOutput() {
                    @Override
                    public void write(OutputStream out) throws IOException, WebApplicationException {
                        RequestTracker.RequestInfo reqInfo = null;
                        Long reqId = RequestTracker.getCurrentRequestId();
                        if (reqId != null) {
                            reqInfo = RequestTracker.getInstance().getRequest(reqId);
                        }
                        try (InputStream is = eagleStream) {
                            byte[] buf = new byte[BUFFER_SIZE];
                            int read;
                            int writes = 0;
                            // reset lastMarkNanos if needed by marking start
                            if (reqInfo != null) reqInfo.markPhase("stream_init");
                            while (true) {
                                read = is.read(buf);
                                if (reqInfo != null) reqInfo.markPhase("stream_read");
                                if (read == -1) break;
                                out.write(buf, 0, read);
                                // Flush periodically so the client receives progress
                                // events instead of waiting for Grizzly's internal
                                // buffer to overflow.
                                if (++writes % FLUSH_INTERVAL == 0) {
                                    out.flush();
                                }
                                if (reqInfo != null) reqInfo.markPhase("stream_write");
                            }
                            out.flush();
                            if (reqInfo != null) reqInfo.markPhase("stream_write");
                        }
                    }
                }).build();
    }

    /**
     * Parse a "bytes=start-end" range header.
     * Returns {start, end} (inclusive) or null if the header is invalid.
     */
    private static long[] parseRange(String rangeHeader, long totalLength) {
        try {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            // Only support single range, not multi-range
            if (spec.contains(",")) return null;

            if (spec.startsWith("-")) {
                // suffix range: "-500" means last 500 bytes
                long suffix = Long.parseLong(spec.substring(1));
                long start = Math.max(0, totalLength - suffix);
                return new long[]{start, totalLength - 1};
            }

            String[] parts = spec.split("-", 2);
            long start = Long.parseLong(parts[0]);
            long end = (parts.length > 1 && !parts[1].isEmpty())
                    ? Long.parseLong(parts[1])
                    : totalLength - 1;

            if (start < 0 || start >= totalLength) return null;
            if (end >= totalLength) end = totalLength - 1;
            if (end < start) return null;

            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void skipFully(InputStream is, long toSkip) throws IOException {
        long remaining = toSkip;
        while (remaining > 0) {
            long skipped = is.skip(remaining);
            if (skipped <= 0) {
                // Fallback: read and discard
                int r = is.read();
                if (r < 0) break;
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static void copyBytes(InputStream is, OutputStream out, long maxBytes) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        long remaining = maxBytes;
        int writes = 0;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = is.read(buf, 0, toRead);
            if (read < 0) break;
            out.write(buf, 0, read);
            remaining -= read;
            if (++writes % FLUSH_INTERVAL == 0) {
                out.flush();
            }
        }
        out.flush();
    }
}
