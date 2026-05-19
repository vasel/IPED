package iped.engine.webapi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.sleuthkit.datamodel.TskCoreException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.engine.config.Configuration;
import iped.engine.data.Category;
import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.data.MultiBitmapBookmarks;
import iped.engine.task.index.IndexItem;
import iped.engine.webapi.SearchStats;
import iped.engine.webapi.json.DataListJSON;
import iped.engine.webapi.json.SourceJSON;

@Tag(name = "Sources")
@Path("sources")
public class Sources {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sources.class);

    public static IPEDMultiSource multiSource = null;
    public static Map<Integer, String> sourceIntToString;
    public static Map<String, Integer> sourceStringToInt;
    public static Map<String, String> sourcePathToStringID;
    private static String sourcesUrl = null;
    private static boolean checkSourcesFlag = false;
    private static boolean warmupFlag = true;
    private static boolean precomputeStatsFlag = true;
    private static SearchStats searchStats;

    public static void init(String urlToAskSources, boolean checkSources, boolean precomputeStats, boolean warmup)
            throws IOException, ParseException {
        long totalStart = System.currentTimeMillis();
        sourcesUrl = urlToAskSources;
        checkSourcesFlag = checkSources;
        warmupFlag = warmup;
        precomputeStatsFlag = precomputeStats;
        sourceIntToString = new HashMap<Integer, String>();
        sourceStringToInt = new HashMap<String, Integer>();
        sourcePathToStringID = new HashMap<String, String>();
        searchStats = null;

        IPEDSource.setUseConsoleForMissingImages(true);
        IndexItem.setUseConsoleForMissingDataSources(true);

        LOGGER.info("Loading sources from {}...", urlToAskSources);
        JSONArray arr = askSources(urlToAskSources);
        int totalSources = arr.size();
        LOGGER.info("Found {} source(s) to load", totalSources);

        boolean confInited = false;
        List<IIPEDSource> sources = new ArrayList<IIPEDSource>();
        int srcIndex = 0;
        for (Object object : arr) {
            srcIndex++;
            JSONObject jsonobj = (JSONObject) object;
            String id = (String) jsonobj.get("id");
            File file = new File(fixUNCPath((String) jsonobj.get("path")));

            sourcePathToStringID.put(file.toString(), id);

            if (!confInited) {
                Configuration.getInstance().loadConfigurables(file + File.separator + "iped", true); //$NON-NLS-1$
                confInited = true;
            }

            File sleuthDb = new File(file, "sleuth.db");
            if (sleuthDb.exists() && (!sleuthDb.canWrite() || !file.canWrite())) {
                LOGGER.warn("[{}/{}] ATENÇÃO: A pasta do caso ou o arquivo sleuth.db em '{}' estão como somente-leitura. " +
                            "Dependendo da sua versão, o IPED precisará copiar o banco de dados inteiro para a pasta temporária " +
                            "para conseguir abri-lo, causando grande lentidão na carga. Recomendamos conceder permissão de ESCRITA na pasta.", 
                            srcIndex, totalSources, file.getAbsolutePath());
            }

            LOGGER.info("[{}/{}] Opening source '{}' at {}...", srcIndex, totalSources, id, file);
            long srcStart = System.currentTimeMillis();
            IPEDSource source = new IPEDSource(file);
            long srcElapsed = System.currentTimeMillis() - srcStart;

            File indexDir = source.getIndex();
            String indexDirStr = indexDir != null ? indexDir.getAbsolutePath() : "Desconhecido";
            double indexSizeMB = indexDir != null ? getFolderSize(indexDir) / (1024.0 * 1024.0) : 0.0;

            LOGGER.info("[{}/{}] Source '{}' loaded (Índice: {}, Tamanho do Índice: {} MB, Total de Arquivos/Itens: {}, Tempo de carregamento: {} ms)", 
                    srcIndex, totalSources, id, indexDirStr, String.format(java.util.Locale.US, "%.2f", indexSizeMB), 
                    source.getTotalItems(), srcElapsed);

            if (srcElapsed > 10000) {
                LOGGER.warn("[{}/{}] O carregamento do source '{}' está levando bastante tempo ({} ms). " + 
                            "Isso geralmente ocorre devido à inicialização interna do banco do Sleuthkit " +
                            "(ex: construção do cache no populateHasChildrenMap), I/O lento do disco, " +
                            "ou cópia do arquivo sleuth.db caso a pasta original seja somente-leitura. " +
                            "Para otimizar, certifique-se de usar discos rápidos (SSD) ou habilitar modo de leitura robusta.", 
                            srcIndex, totalSources, id, srcElapsed);
            }

            if (checkSources) {
                LOGGER.info("[{}/{}] Checking data sources for '{}'...", srcIndex, totalSources, id);
                source.precheckDataSources();
            }
            sources.add(source);
        }

        LOGGER.info("Initializing multi-source index...");
        multiSource = new IPEDMultiSource(sources);

        LOGGER.info("Building source ID mappings...");
        // filling maps using path, to avoid relying on provided order
        for (int i = 0; i < multiSource.getAtomicSources().size(); i++) {
            IIPEDSource source = multiSource.getAtomicSourceBySourceId(i);
            String path = source.getCaseDir().toString();
            String id = sourcePathToStringID.get(path);
            if (sourceStringToInt.containsKey(id)) {
                throw new RuntimeException("duplicated id: " + id);
            }
            sourceStringToInt.put(id, i);
            sourceIntToString.put(i, id);
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        LOGGER.info("All {} source(s) initialized successfully ({}ms total)", totalSources, totalElapsed);

        if (precomputeStatsFlag) {
            buildStats();
        }

        if (warmupFlag) {
            warmUpSources();
        }

        // Precompute device info for all sources so the /deviceinfo endpoint is instant
        DeviceInfo.precomputeAll();
    }

    public static IIPEDSource getSource(String sourceID) {
        if (sourceStringToInt == null || multiSource == null) {
            throw new RuntimeException("Sources not initialized. Call init() first.");
        }
        Integer id = sourceStringToInt.get(sourceID);
        if (id == null) {
            throw new RuntimeException("Source not found: " + sourceID);
        }
        return multiSource.getAtomicSourceBySourceId(id);
    }

    public static SearchStats getSearchStats() {
        return searchStats;
    }

    @Operation(summary = "List sources")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public static DataListJSON<SourceJSON> listSources() throws TskCoreException, IOException {
        if (multiSource == null || sourceIntToString == null) {
            return new DataListJSON<SourceJSON>(new ArrayList<SourceJSON>());
        }
        List<SourceJSON> data = new ArrayList<SourceJSON>();
        for (IIPEDSource source : multiSource.getAtomicSources()) {
            int id = source.getSourceId();
            String sourceID = sourceIntToString.get(id);
            if (sourceID != null) {
                data.add(getone(sourceID));
            }
        }
        return new DataListJSON<SourceJSON>(data);
    }

    @Operation(summary = "Add source")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public synchronized static Response addSource(@Parameter(required = true) SourceJSON sourcejson) {
        String id = sourcejson.getId();
        String path = fixUNCPath(sourcejson.getPath());
        if (sourceStringToInt.containsKey(id)) {
            throw new RuntimeException("duplicated id: " + id);
        }
        sourcePathToStringID.put(path, id);

        List<IPEDSource> sources = multiSource.getAtomicSources();
        int last = sources.size();
        LOGGER.info("Adding new source '{}' at {}...", id, path);
        IPEDSource newSource = new IPEDSource(new File(path));
        if (checkSourcesFlag) {
            LOGGER.info("Checking data sources for '{}'...", id);
            newSource.precheckDataSources();
        }
        sources.add(newSource);
        if (last + 1 != sources.size()) {
            throw new RuntimeException("concurrency error adding source");
        }
        multiSource.init();
        IIPEDSource source = multiSource.getAtomicSourceBySourceId(last);
        String realpath = source.getCaseDir().toString();
        if (!path.equals(realpath)) {
            throw new RuntimeException("error adding source; expected " + path + " got " + realpath);
        }
        sourceStringToInt.put(id, last);
        sourceIntToString.put(last, id);

        if (precomputeStatsFlag) {
            buildStats();
        } else {
            searchStats = null;
        }

        return Response.ok().build();
    }

    @Operation(summary = "Get source's properties")
    @GET
    @Path("{sourceID}")
    @Produces(MediaType.APPLICATION_JSON)
    public static SourceJSON getone(@PathParam("sourceID") String sourceID) throws IOException, TskCoreException {
        SourceJSON result = new SourceJSON();
        IIPEDSource source = getSource(sourceID);
        result.setId(sourceID);
        result.setPath(source.getCaseDir().toString());
        result.setTotalItems(source.getTotalItems());
        result.setIndexDir(source.getIndex() != null ? source.getIndex().toString() : "");
        result.setTotalSizeMB(computeTotalSizeMB(source));
        SearchStats stats = getSearchStats();
        if (stats != null) {
            if (stats.getSourceCategoryCounts().containsKey(sourceID)) {
                result.setCategoryCounts(stats.getSourceCategoryCounts().get(sourceID));
            }
            if (stats.getSourceBookmarkCounts().containsKey(sourceID)) {
                result.setBookmarkCounts(stats.getSourceBookmarkCounts().get(sourceID));
            }
        }
        return result;
    }

    /**
     * Computes total file size in MB by iterating NumericDocValues for the "size"
     * field. This is very fast — no stored fields are loaded, just doc-value
     * column.
     */
    private static double computeTotalSizeMB(IIPEDSource source) {
        try {
            LeafReader reader = source.getLeafReader();
            if (reader == null)
                return 0;
            NumericDocValues sizeValues = reader.getNumericDocValues("size");
            if (sizeValues == null)
                return 0;
            long totalBytes = 0;
            for (int doc = 0; doc < reader.maxDoc(); doc++) {
                if (sizeValues.advanceExact(doc)) {
                    totalBytes += sizeValues.longValue();
                }
            }
            return Math.round(totalBytes / (1024.0 * 1024.0) * 100.0) / 100.0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONArray askSources(String urlToAskSources)
            throws MalformedURLException, IOException, ParseException {
        InputStream in;
        JSONArray result = new JSONArray();
        if ((new File(urlToAskSources)).exists()) {
            in = new FileInputStream(urlToAskSources);
        } else {
            in = (new URL(urlToAskSources)).openConnection().getInputStream();
        }
        try {
            result = (JSONArray) JSONValue.parseWithException(new InputStreamReader(in));
        } finally {
            in.close();
        }
        return result;
    }

    private static synchronized void buildStats() {
        if (multiSource == null) {
            return;
        }
        long start = System.currentTimeMillis();
        Map<String, Integer> sourceTotals = new HashMap<>();
        Map<String, Map<String, Integer>> sourceCategoryCounts = new HashMap<>();
        Map<String, Map<String, Integer>> sourceBookmarkCounts = new HashMap<>();
        Map<String, Integer> categoryTotals = new HashMap<>();
        Map<String, Integer> bookmarkTotals = new HashMap<>();

        for (IIPEDSource src : multiSource.getAtomicSources()) {
            String sid = sourceIntToString.get(src.getSourceId());
            if (sid == null) continue;
            sourceTotals.put(sid, src.getTotalItems());
            sourceCategoryCounts.put(sid, new HashMap<>());
            sourceBookmarkCounts.put(sid, new HashMap<>());

            Category tree = ((IPEDSource) src).getCategoryTree();
            if (tree != null) {
                collectCategoryCounts(tree, sourceCategoryCounts.get(sid), categoryTotals);
            }
        }

        if (multiSource.getMultiBookmarks() != null) {
            Set<String> bookmarkNames = multiSource.getMultiBookmarks().getBookmarkSet();
            for (String name : bookmarkNames) {
                int total = multiSource.getMultiBookmarks().getBookmarkCount(name);
                bookmarkTotals.put(name, total);
            }

            if (multiSource.getMultiBookmarks() instanceof MultiBitmapBookmarks) {
                MultiBitmapBookmarks mbm = (MultiBitmapBookmarks) multiSource.getMultiBookmarks();
                for (String name : bookmarkNames) {
                    Map<Integer, Integer> perSource = mbm.getBookmarkCountBySource(name);
                    for (Map.Entry<Integer, Integer> e : perSource.entrySet()) {
                        String sid = sourceIntToString.get(e.getKey());
                        if (sid != null) {
                            sourceBookmarkCounts.computeIfAbsent(sid, k -> new HashMap<>()).put(name, e.getValue());
                        }
                    }
                }
            }
        }

        searchStats = new SearchStats(sourceTotals, sourceCategoryCounts, sourceBookmarkCounts, categoryTotals,
                bookmarkTotals);
        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("Precomputed stats for {} source(s) in {}ms", sourceTotals.size(), elapsed);
    }

    private static void warmUpSources() {
        long start = System.currentTimeMillis();
        int warmed = 0;
        for (IIPEDSource src : multiSource.getAtomicSources()) {
            try {
                IPEDSearcher searcher = new IPEDSearcher((IPEDSource) src, "*");
                searcher.setNoScoring(true);
                searcher.count();
                // also hit one doc to warm docvalues/stored small footprint
                int[] totalHits = new int[1];
                searcher.searchPaged(0, 1, totalHits);
                warmed++;
            } catch (Exception e) {
                LOGGER.warn("Warmup failed for source {}: {}", src.getSourceId(), e.getMessage());
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("Warmup executed for {} source(s) in {}ms", warmed, elapsed);
    }

    private static void collectCategoryCounts(Category category, Map<String, Integer> perSource,
            Map<String, Integer> aggregate) {
        if (category == null) return;
        int num = category.getNumItems();
        if (num >= 0) {
            perSource.put(category.getName(), num);
            aggregate.merge(category.getName(), num, Integer::sum);
        }
        for (Category child : category.getChildren()) {
            collectCategoryCounts(child, perSource, aggregate);
        }
    }

    @Operation(summary = "Reload sources")
    @POST
    @Path("reload")
    @Produces(MediaType.APPLICATION_JSON)
    public static synchronized Response reloadPost() throws IOException, ParseException {
        return doReload();
    }

    @Operation(summary = "Reload sources")
    @GET
    @Path("reload")
    @Produces(MediaType.APPLICATION_JSON)
    public static synchronized Response reloadGet() throws IOException, ParseException {
        return doReload();
    }

    private static String fixUNCPath(String path) {
        if (path != null && path.startsWith("\\") && !path.startsWith("\\\\")) {
            path = "\\" + path;
        }
        return path;
    }

    private static Response doReload() throws IOException, ParseException {
        if (sourcesUrl == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Sources URL not initialized. Start the server with --sources parameter first.")
                    .build();
        }

        // Close existing multiSource (it will close all internal sources)
        if (multiSource != null) {
            try {
                multiSource.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            multiSource = null;
        }

        // Invalidate caches before reinitializing
        DeviceInfo.invalidateCache();
        SearchResultCache.invalidateAll();

        // Clear maps before reinitializing
        if (sourceIntToString != null)
            sourceIntToString.clear();
        if (sourceStringToInt != null)
            sourceStringToInt.clear();
        if (sourcePathToStringID != null)
            sourcePathToStringID.clear();

        // Reinitialize with the original sources URL
        init(sourcesUrl, checkSourcesFlag, precomputeStatsFlag, warmupFlag);
        return Response.ok().build();
    }

    private static long getFolderSize(File folder) {
        long length = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    length += file.length();
                } else {
                    length += getFolderSize(file);
                }
            }
        }
        return length;
    }
}
