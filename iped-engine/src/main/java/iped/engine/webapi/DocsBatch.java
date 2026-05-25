package iped.engine.webapi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.engine.webapi.json.DocIDJSON;
import iped.engine.webapi.json.DocPropsJSON;

/**
 * Batch endpoint to fetch document properties in a single call.
 *
 * <p>Per-document work is dispatched to a single, process-wide bounded thread
 * pool. Using one shared pool (instead of one per request) keeps total
 * parallelism stable when many concurrent batch requests arrive, avoiding the
 * thread/IO contention that hurts throughput.</p>
 */
@Tag(name = "Documents")
@Path("api/docs/props-batch")
public class DocsBatch {

    private static final int MAX_BATCH = 100;

    /** Shared bounded pool for per-doc property building across ALL batch requests. */
    private static final int BATCH_THREADS = Math.max(2, Integer.parseInt(
            System.getProperty("iped.webapi.docsbatch.threads",
                    String.valueOf(Math.min(16, Runtime.getRuntime().availableProcessors())))));

    private static final ExecutorService BATCH_EXECUTOR = Executors.newFixedThreadPool(BATCH_THREADS, r -> {
        Thread t = new Thread(r, "docs-batch-pool");
        t.setDaemon(true);
        return t;
    });

    @Operation(summary = "Get document properties in batch")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response batchProperties(List<DocIDJSON> docs) {
        if (docs == null || docs.isEmpty() || docs.size() > MAX_BATCH) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        List<Future<DocPropsJSON>> futures = new ArrayList<>(docs.size());
        for (DocIDJSON ref : docs) {
            futures.add(BATCH_EXECUTOR.submit(() -> {
                if (ref == null || ref.getSource() == null) {
                    return null;
                }
                try {
                    IIPEDSource source = Sources.getSource(ref.getSource());
                    return Docs.buildDocProps(source, ref.getSource(), ref.getId(), null);
                } catch (Exception e) {
                    // On any error (not found, IO, etc.) return null for this slot
                    return null;
                }
            }));
        }

        List<DocPropsJSON> result = new ArrayList<>(docs.size());
        for (Future<DocPropsJSON> future : futures) {
            try {
                result.add(future.get());
            } catch (Exception e) {
                result.add(null);
            }
        }

        return Response.ok(result).type(MediaType.APPLICATION_JSON).build();
    }
}
