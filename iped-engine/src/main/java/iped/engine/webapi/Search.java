package iped.engine.webapi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.data.IItemId;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.IPEDSearcher.SearchAfterMultiResult;
import iped.engine.search.IPEDSearcher.SearchAfterResult;
import iped.engine.webapi.json.DocIDJSON;
import iped.engine.webapi.json.SourceToIDsJSON;
import iped.search.SearchResult;

@Tag(name = "Search")
@Path("search")
public class Search {

    private static final Map<String, Integer> MATCH_ALL_COUNT_CACHE = new ConcurrentHashMap<>();

    /** Thread pool for parallel per-source counting/searching. */
    private static final int PARALLEL_THREADS = Integer.parseInt(
            System.getProperty("iped.webapi.search.threads", "4"));
    private static final ExecutorService SEARCH_POOL = Executors.newFixedThreadPool(PARALLEL_THREADS,
            r -> { Thread t = new Thread(r, "search-pool"); t.setDaemon(true); return t; });

    private static final java.util.regex.Pattern CATEGORY_ONLY = java.util.regex.Pattern.compile(
            "(?i)^" + iped.engine.task.index.IndexItem.CATEGORY + ":\"?([^\"]+)\"?$");

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

    @Parameter(description = "Opaque cursor token returned in 'nextCursor' from a previous response. "
            + "When provided, 'start' is ignored and results begin after the cursor position. "
            + "Pass an empty string or omit for the first page.")
    @DefaultValue("")
    @QueryParam("cursor")
    String cursor;

    @Operation(summary = "Search documents",
               description = "Search documents with optional sorting and cursor-based pagination. "
                       + "Use 'cursor' for efficient deep paging instead of large 'start' values. "
                       + "Use 'sortField' to specify a field name from the index (see GET /fields) "
                       + "and 'sortOrder' for direction (asc/desc).")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response doSearch() throws Exception {

        // ----- LRU cache check (only for offset-based requests) -----
        String cacheKey = SearchResultCache.key(q, sourceID, sortField, sortOrder, start, rows);
        boolean useCursor = cursor != null && !cursor.isEmpty();
        if (!useCursor) {
            SourceToIDsJSON cached = SearchResultCache.get(cacheKey);
            if (cached != null) {
                markPhase("cache.hit");
                return Response.ok(cached).type(MediaType.APPLICATION_JSON).build();
            }
        }

        String escapeq = q.replaceAll("/", "\\\\/");
        List<DocIDJSON> docs = new ArrayList<>();
        int total = 0;
        SearchStats stats = Sources.getSearchStats();
        String nextCursor = null;

        boolean isMatchAll = q != null && "*".equals(q.trim());
        String categoryOnly = extractCategoryOnly(q);

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

        markPhase("prepare");

        if (sourceID.equals("")) {
            // ---- All sources ----
            IPEDSearcher searcher = new IPEDSearcher(Sources.multiSource, escapeq);
            int[] totalHits = new int[1];
            // Try to resolve total from precomputed stats BEFORE running the search
            int preTotal = resolvePrecomputedTotal(isMatchAll, categoryOnly, stats, null);

            if (useCursor) {
                ScoreDoc afterDoc = CursorCodec.decode(cursor);
                SearchAfterMultiResult saResult = searcher.multiSearchAfterPaged(rows, totalHits, afterDoc, sort);
                total = preTotal >= 0 ? preTotal : totalHits[0];
                IItemId[] items = saResult.getItems();
                for (IItemId id : items) {
                    docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
                }
                nextCursor = CursorCodec.encode(saResult.getLastScoreDoc());
                if (items.length < rows) nextCursor = null; // last page
            } else {
                IItemId[] page = searcher.multiSearchPaged(start, rows, totalHits, sort, preTotal);
                total = preTotal >= 0 ? preTotal : totalHits[0];
                for (IItemId id : page) {
                    docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
                }
            }
            markPhase("multi.search");

        } else if (sourceID.contains(",")) {
            // ---- Multiple explicit sources ----
            String[] sourceIds = sourceID.split(",");

            if (sort != null) {
                // Sorted multi-source: global search with post-filter
                IPEDSearcher searcher = new IPEDSearcher(Sources.multiSource, escapeq);
                int[] totalHits = new int[1];

                if (useCursor) {
                    ScoreDoc afterDoc = CursorCodec.decode(cursor);
                    SearchAfterMultiResult saResult = searcher.multiSearchAfterPaged(rows, totalHits, afterDoc, sort);
                    total = resolveTotalMulti(isMatchAll, categoryOnly, stats, totalHits[0], sourceID);
                    Set<Integer> allowed = allowedSourceIds(sourceIds);
                    IItemId[] items = saResult.getItems();
                    for (IItemId id : items) {
                        if (allowed.contains(id.getSourceId())) {
                            docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
                        }
                    }
                    nextCursor = CursorCodec.encode(saResult.getLastScoreDoc());
                    if (items.length < rows) nextCursor = null;
                } else {
                    IItemId[] page = searcher.multiSearchPaged(start, rows, totalHits, sort);
                    total = resolveTotalMulti(isMatchAll, categoryOnly, stats, totalHits[0], sourceID);
                    Set<Integer> allowed = allowedSourceIds(sourceIds);
                    for (IItemId id : page) {
                        if (allowed.contains(id.getSourceId())) {
                            docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
                        }
                    }
                }
                markPhase("multi.sorted.search");

            } else {
                // Unsorted multi-source: parallel per-source count, then sequential page fetch
                List<SourceInfo> sourcesInfo = new ArrayList<>();

                // ---- Parallel counting via CompletableFuture ----
                List<CompletableFuture<SourceInfo>> futures = new ArrayList<>();
                for (String srcId : sourceIds) {
                    String trimmedSrcId = srcId.trim();
                    if (trimmedSrcId.isEmpty()) continue;
                    final String finalEscapeq = escapeq;
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            IIPEDSource source = Sources.getSource(trimmedSrcId);
                            if (source == null) throw new RuntimeException("Source not found: " + trimmedSrcId);
                            IPEDSearcher s = new IPEDSearcher((IPEDSource) source, finalEscapeq);
                            int sourceTotal = resolveSourceTotal(trimmedSrcId, s, isMatchAll, categoryOnly, stats);
                            return new SourceInfo(trimmedSrcId, (IPEDSource) source, sourceTotal);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, SEARCH_POOL));
                }
                for (CompletableFuture<SourceInfo> f : futures) {
                    SourceInfo si = f.join();
                    sourcesInfo.add(si);
                    total += si.total;
                }
                markPhase("parallel.count");

                // ---- Sequential page fetch ----
                int currentPosition = 0;
                int collected = 0;
                for (SourceInfo srcInfo : sourcesInfo) {
                    if (currentPosition + srcInfo.total <= start) {
                        currentPosition += srcInfo.total;
                        continue;
                    }
                    if (collected >= rows) break;

                    int srcStart = Math.max(0, start - currentPosition);
                    int remaining = rows - collected;
                    int[] th = new int[1];
                    IPEDSearcher searcher = new IPEDSearcher(srcInfo.source, escapeq);
                    SearchResult result = searcher.searchPaged(srcStart, remaining, th);
                    int[] ids = result.getIds();
                    markPhase("search." + srcInfo.sourceId);

                    for (int id : ids) {
                        docs.add(new DocIDJSON(srcInfo.sourceId, id));
                        collected++;
                        if (collected >= rows) break;
                    }
                    currentPosition += srcInfo.total;
                }
            }

        } else {
            // ---- Single source ----
            IPEDSource source = (IPEDSource) Sources.getSource(sourceID);
            if (source == null) throw new RuntimeException("Source not found: " + sourceID);
            int preTotal = resolvePrecomputedSourceTotal(sourceID, isMatchAll, categoryOnly, stats);

            if (useCursor) {
                ScoreDoc afterDoc = CursorCodec.decode(cursor);
                int[] totalHits = new int[1];
                IPEDSearcher searcher = new IPEDSearcher(source, escapeq);
                SearchAfterResult saResult = searcher.searchAfterPaged(rows, totalHits, afterDoc, sort);
                total = preTotal >= 0 ? preTotal : totalHits[0];
                int[] ids = saResult.getIds();
                for (int id : ids) {
                    docs.add(new DocIDJSON(sourceID, id));
                }
                nextCursor = CursorCodec.encode(saResult.getLastScoreDoc());
                if (ids.length < rows) nextCursor = null;
            } else {
                int[] totalHits = new int[1];
                IPEDSearcher searcher = new IPEDSearcher(source, escapeq);
                SearchResult result = searcher.searchPaged(start, rows, totalHits, sort, preTotal);
                total = preTotal >= 0 ? preTotal : totalHits[0];
                int[] ids = result.getIds();
                for (int id : ids) {
                    docs.add(new DocIDJSON(sourceID, id));
                }
            }
            markPhase("search." + sourceID);
        }

        markPhase("result.build");

        SourceToIDsJSON body = new SourceToIDsJSON(docs, total, start, rows);
        body.setNextCursor(nextCursor);

        // ----- LRU cache store (only for offset-based, non-cursor requests) -----
        if (!useCursor) {
            SearchResultCache.put(cacheKey, body);
        }

        return Response.ok(body).type(MediaType.APPLICATION_JSON).build();
    }

    // ========== Total resolvers (use precomputed stats when possible) ==========

    /**
     * Try to resolve the total count from precomputed stats BEFORE running any Lucene query.
     * Returns >= 0 if the total is known, or -1 if a live count must be performed.
     */
    private int resolvePrecomputedTotal(boolean isMatchAll, String categoryOnly, SearchStats stats,
            String singleSourceID) {
        if (isMatchAll && stats != null) {
            if (singleSourceID != null && stats.getSourceTotals().containsKey(singleSourceID)) {
                return stats.getSourceTotals().get(singleSourceID);
            }
            if (singleSourceID == null) {
                return stats.getSourceTotals().values().stream().mapToInt(Integer::intValue).sum();
            }
        }
        if (categoryOnly != null && stats != null && stats.getCategoryTotals().containsKey(categoryOnly)) {
            return stats.getCategoryTotals().get(categoryOnly);
        }
        return -1; // unknown, must count live
    }

    /**
     * Try to resolve the total count for a single source from precomputed stats.
     * Returns >= 0 if the total is known, or -1 if a live count must be performed.
     */
    private int resolvePrecomputedSourceTotal(String sid, boolean isMatchAll, String categoryOnly,
            SearchStats stats) {
        if (isMatchAll && stats != null && stats.getSourceTotals().containsKey(sid)) {
            return stats.getSourceTotals().get(sid);
        }
        if (categoryOnly != null && stats != null && stats.getSourceCategoryCounts().containsKey(sid)) {
            Integer v = stats.getSourceCategoryCounts().get(sid).get(categoryOnly);
            if (v != null) return v;
        }
        return -1; // unknown, must count live
    }

    private int resolveTotal(boolean isMatchAll, String categoryOnly, SearchStats stats, int liveTotalHits,
            String singleSourceID) {
        if (isMatchAll && stats != null) {
            if (singleSourceID != null && stats.getSourceTotals().containsKey(singleSourceID)) {
                return stats.getSourceTotals().get(singleSourceID);
            }
            return stats.getSourceTotals().values().stream().mapToInt(Integer::intValue).sum();
        }
        if (categoryOnly != null && stats != null && stats.getCategoryTotals().containsKey(categoryOnly)) {
            return stats.getCategoryTotals().get(categoryOnly);
        }
        return liveTotalHits;
    }

    private int resolveTotalMulti(boolean isMatchAll, String categoryOnly, SearchStats stats, int liveTotalHits,
            String sourceIDsCsv) {
        if (isMatchAll && stats != null) {
            return sumSelectedTotals(stats, sourceIDsCsv);
        }
        if (categoryOnly != null && stats != null) {
            return sumSelectedCategoryTotals(stats, sourceIDsCsv, categoryOnly);
        }
        return liveTotalHits;
    }

    private int resolveSourceTotal(String sid, IPEDSearcher searcher, boolean isMatchAll, String categoryOnly,
            SearchStats stats) throws IOException {
        if (isMatchAll && stats != null && stats.getSourceTotals().containsKey(sid)) {
            return stats.getSourceTotals().get(sid);
        }
        if (categoryOnly != null && stats != null && stats.getSourceCategoryCounts().containsKey(sid)) {
            Integer v = stats.getSourceCategoryCounts().get(sid).get(categoryOnly);
            if (v != null) return v;
        }
        return cachedMatchAllCount(sid, searcher, isMatchAll);
    }

    // ========== Helpers ==========

    private Set<Integer> allowedSourceIds(String[] sourceIds) {
        Set<Integer> allowed = new HashSet<>();
        for (String srcId : sourceIds) {
            String trimmed = srcId.trim();
            if (trimmed.isEmpty()) continue;
            IIPEDSource s = Sources.getSource(trimmed);
            if (s != null) allowed.add(s.getSourceId());
        }
        return allowed;
    }

    private int sumSelectedTotals(SearchStats stats, String sourceIDsCsv) {
        int sum = 0;
        for (String srcId : sourceIDsCsv.split(",")) {
            String trimmed = srcId.trim();
            if (trimmed.isEmpty()) continue;
            Integer val = stats.getSourceTotals().get(trimmed);
            if (val != null) sum += val;
        }
        return sum;
    }

    private int sumSelectedCategoryTotals(SearchStats stats, String sourceIDsCsv, String category) {
        int sum = 0;
        for (String srcId : sourceIDsCsv.split(",")) {
            String trimmed = srcId.trim();
            if (trimmed.isEmpty()) continue;
            Map<String, Integer> perCat = stats.getSourceCategoryCounts().get(trimmed);
            if (perCat != null) {
                Integer val = perCat.get(category);
                if (val != null) sum += val;
            }
        }
        return sum;
    }

    private String extractCategoryOnly(String query) {
        if (query == null) return null;
        java.util.regex.Matcher m = CATEGORY_ONLY.matcher(query.trim());
        return m.find() ? m.group(1) : null;
    }

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

    private void markPhase(String name) {
        RequestTracker.RequestInfo info = currentRequest();
        if (info != null) info.markPhase(name);
    }

    private RequestTracker.RequestInfo currentRequest() {
        Long id = RequestTracker.getCurrentRequestId();
        return id != null ? RequestTracker.getInstance().getRequest(id) : null;
    }

    private int cachedMatchAllCount(String sourceId, IPEDSearcher searcher, boolean isMatchAll) throws IOException {
        if (!isMatchAll) return searcher.count();
        Integer cached = MATCH_ALL_COUNT_CACHE.get(sourceId);
        if (cached != null) return cached;
        int counted = searcher.count();
        MATCH_ALL_COUNT_CACHE.put(sourceId, counted);
        return counted;
    }
}
