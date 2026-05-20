package iped.engine.webapi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
 */
@Tag(name = "Documents")
@Path("api/docs/props-batch")
public class DocsBatch {

    private static final int MAX_BATCH = 100;
    private static final java.util.concurrent.ExecutorService BATCH_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

    @Operation(summary = "Get document properties in batch")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response batchProperties(List<DocIDJSON> docs) {
        if (docs == null || docs.isEmpty() || docs.size() > MAX_BATCH) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        List<java.util.concurrent.Future<DocPropsJSON>> futures = new ArrayList<>(docs.size());
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
        for (java.util.concurrent.Future<DocPropsJSON> future : futures) {
            try {
                result.add(future.get());
            } catch (Exception e) {
                result.add(null);
            }
        }

        return Response.ok(result).type(MediaType.APPLICATION_JSON).build();
    }
}
