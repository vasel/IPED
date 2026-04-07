package iped.engine.webapi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.BytesRef;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.engine.data.IPEDSource;
import iped.engine.task.ThumbTask;
import iped.engine.util.Util;
import iped.parsers.util.MetadataUtil;
import iped.properties.BasicProps;

@Tag(name = "Documents")
@Path("sources/{sourceID}/docs/{id}/thumb")
public class Thumbnail {

    private static final long THUMB_TIMEOUT_MS = 1000;

    private static final ExecutorService thumbExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "thumb-worker");
        t.setDaemon(true);
        return t;
    });

    @Operation(summary = "Get document's thumbnail")
    @GET
    @Produces("image/jpg")
    public Response content(@PathParam("sourceID") String sourceID, @PathParam("id") int id) {

        IPEDSource source = (IPEDSource) Sources.getSource(sourceID);

        Future<byte[]> future = thumbExecutor.submit(new ThumbLoader(source, id));
        try {
            byte[] thumb = future.get(THUMB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (thumb == null || thumb.length == 0) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Thumbnail not available for doc " + id)
                        .type("text/plain")
                        .build();
            }
            return Response.ok(thumb, "image/jpg").build();

        } catch (TimeoutException e) {
            future.cancel(true);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Thumbnail retrieval timed out (>" + THUMB_TIMEOUT_MS + "ms)")
                    .type("text/plain")
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Request interrupted")
                    .type("text/plain")
                    .build();

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ArrayIndexOutOfBoundsException) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Document not found: " + id)
                        .type("text/plain")
                        .build();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error retrieving thumbnail: " + cause.getMessage())
                    .type("text/plain")
                    .build();
        }
    }

    /**
     * Loads thumbnail directly from Lucene stored fields and/or disk,
     * bypassing the expensive full IndexItem.getItem() reconstruction.
     */
    private static class ThumbLoader implements Callable<byte[]> {

        private final IPEDSource source;
        private final int itemId;

        ThumbLoader(IPEDSource source, int itemId) {
            this.source = source;
            this.itemId = itemId;
        }

        @Override
        public byte[] call() throws Exception {
            int luceneId = source.getLuceneId(itemId);
            IndexSearcher searcher = source.getSearcher();

            // First try: read THUMB binary directly from Lucene stored field
            Set<String> fields = new HashSet<>();
            fields.add(BasicProps.THUMB);
            fields.add(ThumbTask.HAS_THUMB);
            fields.add(BasicProps.HASH);
            fields.add(BasicProps.CONTENTTYPE);

            Document doc = searcher.doc(luceneId, fields);

            // Check if thumb is stored inline in Lucene
            BytesRef thumbRef = doc.getBinaryValue(BasicProps.THUMB);
            if (thumbRef != null && thumbRef.length > 0) {
                // BytesRef may share underlying array, copy the relevant portion
                byte[] result = new byte[thumbRef.length];
                System.arraycopy(thumbRef.bytes, thumbRef.offset, result, 0, thumbRef.length);
                return result;
            }

            // Fallback: try to read thumb file from disk using hash
            String hasThumb = doc.get(ThumbTask.HAS_THUMB);
            String hash = doc.get(BasicProps.HASH);

            if (hash != null && !hash.isEmpty()) {
                String contentType = doc.get(BasicProps.CONTENTTYPE);
                File moduleDir = source.getModuleDir();

                // Try image thumbs folder first
                if (contentType != null && MetadataUtil.isImageType(org.apache.tika.mime.MediaType.parse(contentType))) {
                    File thumbFile = Util.getFileFromHash(
                            new File(moduleDir, ThumbTask.THUMBS_FOLDER_NAME),
                            hash, ThumbTask.THUMB_EXT);
                    if (thumbFile.exists() && thumbFile.length() > 0) {
                        return Files.readAllBytes(thumbFile.toPath());
                    }
                }

                // Try video preview folder
                if (contentType != null && MetadataUtil.isVideoType(org.apache.tika.mime.MediaType.parse(contentType))) {
                    File thumbFile = Util.getFileFromHash(
                            new File(moduleDir, iped.engine.preview.PreviewConstants.VIEW_FOLDER_NAME),
                            hash, iped.engine.task.video.VideoThumbTask.PREVIEW_EXT);
                    if (thumbFile.exists() && thumbFile.length() > 0) {
                        return Files.readAllBytes(thumbFile.toPath());
                    }
                }

                // Generic: try thumbs folder regardless of content type
                File thumbFile = Util.getFileFromHash(
                        new File(moduleDir, ThumbTask.THUMBS_FOLDER_NAME),
                        hash, ThumbTask.THUMB_EXT);
                if (thumbFile.exists() && thumbFile.length() > 0) {
                    return Files.readAllBytes(thumbFile.toPath());
                }
            }

            return null;
        }
    }
}