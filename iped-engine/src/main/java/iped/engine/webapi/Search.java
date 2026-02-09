package iped.engine.webapi;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import iped.data.IIPEDSource;
import iped.data.IItemId;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.webapi.json.DocIDJSON;
import iped.engine.webapi.json.SourceToIDsJSON;
import iped.search.IIPEDSearcher;
import iped.search.IMultiSearchResult;
import iped.search.SearchResult;

@Api(value = "Search")
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

    @ApiOperation(value = "Search documents")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SourceToIDsJSON doSearch() throws Exception {
        String escapeq = q.replaceAll("/", "\\\\/");
        List<DocIDJSON> docs = new ArrayList<DocIDJSON>();
        int total = 0;
        
        // Check if this is a "match all" query
        boolean isMatchAll = "*".equals(q.trim()) || "*:*".equals(q.trim());
        
        if (sourceID.equals("")) {
            // Search across all sources
            IPEDSearcher searcher = new IPEDSearcher(Sources.multiSource, escapeq);
            IMultiSearchResult result = searcher.multiSearch();
            total = result.getLength();
            int count = 0;
            for (IItemId id : result.getIterator()) {
                if (count >= start && count < start + rows) {
                    docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
                }
                count++;
                if (count >= start + rows) {
                    break;
                }
            }
        } else if (sourceID.contains(",")) {
            // Multiple sources - optimized search
            String[] sourceIds = sourceID.split(",");
            List<SourceInfo> sourcesInfo = new ArrayList<>();
            
            // First pass: get total counts for each source (fast for match-all queries)
            for (String srcId : sourceIds) {
                String trimmedSrcId = srcId.trim();
                if (trimmedSrcId.isEmpty()) continue;
                
                IIPEDSource source = Sources.getSource(trimmedSrcId);
                if (source == null) {
                    throw new RuntimeException("Source not found: " + trimmedSrcId);
                }
                
                int sourceTotal;
                if (isMatchAll) {
                    // For "*" queries, use cached total - no search needed
                    sourceTotal = source.getTotalItems();
                } else {
                    // For other queries, we need to search to get the count
                    IIPEDSearcher searcher = new IPEDSearcher((IPEDSource) source, escapeq);
                    SearchResult result = searcher.search();
                    sourceTotal = result.getLength();
                    // Store the result to avoid searching again
                    sourcesInfo.add(new SourceInfo(trimmedSrcId, (IPEDSource) source, sourceTotal, result.getIds()));
                    total += sourceTotal;
                    continue;
                }
                
                sourcesInfo.add(new SourceInfo(trimmedSrcId, (IPEDSource) source, sourceTotal, null));
                total += sourceTotal;
            }
            
            // Second pass: only search sources that contribute to the requested page
            int currentPosition = 0;
            int collected = 0;
            
            for (SourceInfo srcInfo : sourcesInfo) {
                int srcLength = srcInfo.total;
                
                // Skip this source entirely if we haven't reached the start yet
                if (currentPosition + srcLength <= start) {
                    currentPosition += srcLength;
                    continue;
                }
                
                // Check if we've already collected all requested rows
                if (collected >= rows) {
                    currentPosition += srcLength;
                    continue;
                }
                
                // Calculate where to start and end within this source
                int srcStart = Math.max(0, start - currentPosition);
                int remaining = rows - collected;
                int srcEnd = Math.min(srcLength, srcStart + remaining);
                
                // Get the IDs - either from cache or by searching
                int[] ids = srcInfo.ids;
                if (ids == null) {
                    // Need to search this source (only happens for match-all queries)
                    IIPEDSearcher searcher = new IPEDSearcher(srcInfo.source, escapeq);
                    SearchResult result = searcher.search();
                    ids = result.getIds();
                }
                
                // Collect the documents for this page
                for (int i = srcStart; i < srcEnd && i < ids.length; i++) {
                    docs.add(new DocIDJSON(srcInfo.sourceId, ids[i]));
                    collected++;
                    if (collected >= rows) break;
                }
                
                currentPosition += srcLength;
                if (collected >= rows) break;
            }
        } else {
            // Single source
            IPEDSource source = (IPEDSource) Sources.getSource(sourceID);
            if (source == null) {
                throw new RuntimeException("Source not found: " + sourceID);
            }
            IIPEDSearcher searcher = new IPEDSearcher(source, escapeq);
            SearchResult result = searcher.search();
            int[] ids = result.getIds();
            total = ids.length;
            int end = Math.min(start + rows, total);
            for (int i = start; i < end; i++) {
                docs.add(new DocIDJSON(sourceID, ids[i]));
            }
        }

        return new SourceToIDsJSON(docs, total, start, rows);
    }
    
    /**
     * Helper class to hold source information and optionally cached search results.
     */
    private static class SourceInfo {
        final String sourceId;
        final IPEDSource source;
        final int total;
        final int[] ids; // May be null if not yet searched
        
        SourceInfo(String sourceId, IPEDSource source, int total, int[] ids) {
            this.sourceId = sourceId;
            this.source = source;
            this.total = total;
            this.ids = ids;
        }
    }
}
