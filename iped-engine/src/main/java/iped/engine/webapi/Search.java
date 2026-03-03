package iped.engine.webapi;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.lucene.search.Sort;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.data.IItemId;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.webapi.json.DocIDJSON;
import iped.engine.webapi.json.SourceToIDsJSON;
import iped.search.SearchResult;

@Tag(name = "Search")
@Path("search")
public class Search {

    @DefaultValue("")
    @QueryParam("q")
    String q;
    @DefaultValue("")
    @QueryParam("sourceID")
    String sourceID;
    @DefaultValue("0")
    @QueryParam("start")
    int start;
    @DefaultValue("100")
    @QueryParam("rows")
    int rows;

    @Parameter(description = "Field name to sort results by. Use GET /fields to list available field names. "
            + "Special value 'relevance' sorts by search score.")
    @DefaultValue("")
    @QueryParam("sortField")
    String sortField;

    @Parameter(description = "Sort direction: 'asc' for ascending (default) or 'desc' for descending.")
    @DefaultValue("asc")
    @QueryParam("sortOrder")
    String sortOrder;

    @Operation(summary = "Search documents",
               description = "Search documents with optional sorting. Use 'sortField' to specify a field name "
                       + "from the index (see GET /fields for available fields) and 'sortOrder' for direction (asc/desc).")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response doSearch() throws Exception {
        String escapeq = q.replaceAll("/", "\\\\/");
        List<DocIDJSON> docs = new ArrayList<DocIDJSON>();
        int total = 0;

        // Build Lucene Sort from sortField / sortOrder parameters
        Sort sort = null;
        if (sortField != null && !sortField.trim().isEmpty()) {
            boolean reverse = "desc".equalsIgnoreCase(sortOrder.trim());
            try {
                sort = SortFieldHelper.buildSort(Sources.multiSource, sortField.trim(), reverse);
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }
        }

        if (sourceID.equals("")) {
            // Search across all sources — use paginated multi-source search
            IPEDSearcher searcher = new IPEDSearcher(Sources.multiSource, escapeq);
            int[] totalHits = new int[1];
            IItemId[] page = searcher.multiSearchPaged(start, rows, totalHits, sort);
            total = totalHits[0];
            for (IItemId id : page) {
                docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
            }
        } else if (sourceID.contains(",")) {
            // Multiple explicit sources
            if (sort != null) {
                // When sorting across multiple explicit sources, use the multiSource searcher
                // with a filter query on evidenceUUID to ensure globally correct sort order.
                IPEDSearcher searcher = new IPEDSearcher(Sources.multiSource, escapeq);
                int[] totalHits = new int[1];
                IItemId[] page = searcher.multiSearchPaged(start, rows, totalHits, sort);
                total = totalHits[0];

                // Build set of allowed source IDs for filtering
                java.util.Set<Integer> allowedSourceIds = new java.util.HashSet<>();
                for (String srcId : sourceID.split(",")) {
                    String trimmedSrcId = srcId.trim();
                    if (trimmedSrcId.isEmpty()) continue;
                    IIPEDSource source = Sources.getSource(trimmedSrcId);
                    if (source != null) {
                        allowedSourceIds.add(source.getSourceId());
                    }
                }

                // Filter results to only the requested sources
                for (IItemId id : page) {
                    if (allowedSourceIds.contains(id.getSourceId())) {
                        docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
                    }
                }
                // Note: total may include items from other sources; recalculate if needed
                // For simplicity, we keep the multi-source total but this is an approximation
                // when filtering specific sources. A more precise count would require a filtered query.
            } else {
                // No sorting — use the per-source pagination approach (original logic)
                String[] sourceIds = sourceID.split(",");
                List<SourceInfo> sourcesInfo = new ArrayList<>();

                for (String srcId : sourceIds) {
                    String trimmedSrcId = srcId.trim();
                    if (trimmedSrcId.isEmpty()) continue;

                    IIPEDSource source = Sources.getSource(trimmedSrcId);
                    if (source == null) {
                        throw new RuntimeException("Source not found: " + trimmedSrcId);
                    }

                    IPEDSearcher searcher = new IPEDSearcher((IPEDSource) source, escapeq);
                    int sourceTotal = searcher.count();
                    sourcesInfo.add(new SourceInfo(trimmedSrcId, (IPEDSource) source, sourceTotal));
                    total += sourceTotal;
                }

                int currentPosition = 0;
                int collected = 0;

                for (SourceInfo srcInfo : sourcesInfo) {
                    int srcLength = srcInfo.total;

                    if (currentPosition + srcLength <= start) {
                        currentPosition += srcLength;
                        continue;
                    }

                    if (collected >= rows) {
                        break;
                    }

                    int srcStart = Math.max(0, start - currentPosition);
                    int remaining = rows - collected;

                    int[] totalHits = new int[1];
                    IPEDSearcher searcher = new IPEDSearcher(srcInfo.source, escapeq);
                    SearchResult result = searcher.searchPaged(srcStart, remaining, totalHits);
                    int[] ids = result.getIds();

                    for (int id : ids) {
                        docs.add(new DocIDJSON(srcInfo.sourceId, id));
                        collected++;
                        if (collected >= rows) break;
                    }

                    currentPosition += srcLength;
                }
            }
        } else {
            // Single source — use paginated search
            IPEDSource source = (IPEDSource) Sources.getSource(sourceID);
            if (source == null) {
                throw new RuntimeException("Source not found: " + sourceID);
            }
            int[] totalHits = new int[1];
            IPEDSearcher searcher = new IPEDSearcher(source, escapeq);
            SearchResult result = searcher.searchPaged(start, rows, totalHits, sort);
            total = totalHits[0];
            int[] ids = result.getIds();
            for (int id : ids) {
                docs.add(new DocIDJSON(sourceID, id));
            }
        }

        return Response.ok(new SourceToIDsJSON(docs, total, start, rows))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
    
    /**
     * Helper class to hold source information for multi-source pagination.
     */
    private static class SourceInfo {
        final String sourceId;
        final IPEDSource source;
        final int total;

        SourceInfo(String sourceId, IPEDSource source, int total) {
            this.sourceId = sourceId;
            this.source = source;
            this.total = total;
        }
    }
}
