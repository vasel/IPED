package iped.engine.webapi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.config.Configuration;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDSource;
import iped.engine.graph.GraphFileWriter;
import iped.engine.graph.GraphGenerator;
import iped.engine.graph.GraphImportRunner.ImportListener;
import iped.engine.graph.GraphService;
import iped.engine.graph.GraphServiceFactoryImpl;
import iped.engine.graph.GraphTask;
import iped.engine.graph.GraphTaskConfig;
import iped.utils.IOUtil;

/**
 * Handles initialization of the graph database for the web API.
 * Supports both single-case and multi-case scenarios.
 */
public class MultiCaseGraphLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiCaseGraphLoader.class);

    private static GraphService graphService;
    private static boolean initialized = false;
    private static boolean enabled = false;

    /**
     * Initialize the graph service using the sources from Sources class.
     * For single case: uses the graph directly from source/iped/neo4j
     * For multi-case: creates a unified graph combining all cases
     */
    public static synchronized void init() throws Exception {
        if (initialized) {
            return;
        }

        if (Sources.multiSource == null) {
            throw new RuntimeException("Sources not initialized. Call Sources.init() first.");
        }

        List<IPEDSource> sources = Sources.multiSource.getAtomicSources();
        if (sources.isEmpty()) {
            LOGGER.warn("No sources available for graph initialization");
            return;
        }

        // Check if any source has graph enabled
        File firstModuleDir = sources.get(0).getModuleDir();
        Configuration.getInstance().loadConfigurables(firstModuleDir.getAbsolutePath(), true);
        GraphTaskConfig config = ConfigurationManager.get().findObject(GraphTaskConfig.class);

        if (config == null || !config.isEnabled()) {
            LOGGER.info("Graph task is not enabled in configuration");
            return;
        }

        long t = System.currentTimeMillis();
        LOGGER.info("Initializing graph database for {} source(s)...", sources.size());

        File graphHome;
        if (sources.size() == 1) {
            IPEDSource single = sources.get(0);
            LOGGER.info("Attempting to initialize single-case graph for {}", single.getCaseDir());
            graphHome = resolveGraphHome(single);
            if (graphHome == null) {
                LOGGER.warn("Graph database assets not found for case {}", single.getCaseDir());
                initialized = true;
                return;
            }
        } else {
            // Multi-case: create unified graph
            graphHome = createMultiCaseGraph(sources);
        }

        if (graphHome != null && initGraphService(graphHome)) {
            enabled = true;
            LOGGER.info("Graph database initialized in {}s", (System.currentTimeMillis() - t) / 1000);
        }

        initialized = true;
    }

    /**
     * Creates a unified graph for multiple cases.
     */
    private static File createMultiCaseGraph(List<IPEDSource> sources) throws Exception {
        // Generate hash from case names (same algorithm as iped-app)
        String caseNames = sources.stream()
                .map(c -> c.getCaseDir().getName())
                .sorted()
                .collect(Collectors.joining("-"));
        String hash = DigestUtils.md5Hex(caseNames);

        // Create multi-case graph directory
        String suffix = "iped-multicases/multicase-" + hash + "/graph";
        File firstCaseDir = sources.get(0).getCaseDir();
        File multiCaseGraphPath = new File(firstCaseDir.getParentFile(), suffix);

        if (!multiCaseGraphPath.getParentFile().exists() && !multiCaseGraphPath.getParentFile().mkdirs()) {
            multiCaseGraphPath = new File(System.getProperty("java.io.tmpdir"), suffix);
        }

        File graphDataDir = new File(multiCaseGraphPath, GraphTask.DB_DATA_DIR);

        // Check if we need to rebuild the graph
        boolean needsRebuild = !graphDataDir.exists();
        if (!needsRebuild) {
            File csvDir = new File(multiCaseGraphPath, GraphTask.CSVS_DIR);
            needsRebuild = !csvDir.exists();
        }

        if (needsRebuild) {
            LOGGER.info("Creating multi-case graph at: {} from cases: {}", multiCaseGraphPath,
                    caseNames);

            if (multiCaseGraphPath.exists()) {
                IOUtil.deleteDirectory(multiCaseGraphPath, false);
            }

            File graphHomeTemp = new File(multiCaseGraphPath.getAbsolutePath() + "_temp");
            graphHomeTemp = graphHomeTemp.getCanonicalFile();

            if (graphHomeTemp.exists()) {
                IOUtil.deleteDirectory(graphHomeTemp, false);
            }

            // Collect CSV paths from all cases
            List<File> csvParents = collectCsvParents(sources);
            if (csvParents == null) {
                return null;
            }

            // Prepare unified CSVs
            File preparedCSVs = new File(graphHomeTemp, GraphTask.CSVS_DIR);
            LOGGER.info("Preparing merged CSV directory {} from sources:");
            for (int i = 0; i < sources.size(); i++) {
                LOGGER.info("  Case {} -> CSV {}", sources.get(i).getCaseDir(), csvParents.get(i));
            }
            try {
                GraphFileWriter.prepareMultiCaseCSVs(preparedCSVs, csvParents);
            } catch (Exception e) {
                LOGGER.error("Failed preparing CSVs at {} using parents {}", preparedCSVs, csvParents, e);
                throw e;
            }

            // Generate the graph
            GraphGenerator graphGenerator = new GraphGenerator();
            boolean success = false;
            try {
                success = graphGenerator.generate(new LogImportListener(), graphHomeTemp, preparedCSVs.listFiles());
            } catch (Exception e) {
                LOGGER.error("Graph generation failed at {} using CSVs {}", graphHomeTemp, preparedCSVs, e);
                throw e;
            }

            if (success) {
                Files.move(graphHomeTemp.toPath(), multiCaseGraphPath.toPath());
                LOGGER.info("Multi-case graph created successfully");
            } else {
                LOGGER.error("Failed to create multi-case graph");
                return null;
            }
        } else {
            LOGGER.info("Using existing multi-case graph at: {}", multiCaseGraphPath);
        }

        return multiCaseGraphPath;
    }

    private static List<File> collectCsvParents(List<IPEDSource> sources) {
        List<File> csvParents = new ArrayList<>();
        for (IPEDSource source : sources) {
            File csvParent = resolveCsvParent(source);
            if (csvParent == null) {
                LOGGER.error("Graph CSV folder not found for case {}", source.getCaseDir());
                return null;
            }
            LOGGER.debug("Using CSV base {} for case {}", csvParent.getAbsolutePath(),
                    source.getCaseDir());
            csvParents.add(csvParent);
        }
        return csvParents;
    }

    private static File resolveCsvParent(IPEDSource source) {
        File[] candidates = new File[] {
                new File(source.getModuleDir(), GraphTask.CSVS_PATH),
                new File(source.getCaseDir(), IPEDSource.MODULE_DIR + "/" + GraphTask.CSVS_PATH),
                new File(source.getCaseDir(), GraphTask.CSVS_PATH) };

        for (File candidate : candidates) {
            LOGGER.debug("Checking CSV candidate {} for case {}", candidate.getAbsolutePath(),
                    source.getCaseDir());
            if (candidate.exists() && candidate.isDirectory()) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Initialize the GraphService with the given home directory.
     */
    private static boolean initGraphService(File neo4jHome) {
        File originalHome = neo4jHome;
        try {
            LOGGER.info("Loading Neo4j database from {}", neo4jHome.getAbsolutePath());

            File dbDataDir = new File(neo4jHome, GraphTask.DB_DATA_DIR);
            if (!dbDataDir.exists()) {
                LOGGER.error("Graph database not found: {}", dbDataDir.getAbsolutePath());
                return false;
            }

            // If read-only, copy to temp folder
            if (!IOUtil.canWrite(dbDataDir)) {
                neo4jHome = copyToTempFolder(dbDataDir);
            }

            // Neo4j needs canonical path
            neo4jHome = neo4jHome.getCanonicalFile();

            graphService = GraphServiceFactoryImpl.getInstance().getGraphService();
            graphService.start(neo4jHome);

            LOGGER.info("Neo4j database loaded successfully from {}", neo4jHome.getAbsolutePath());

            return true;
        } catch (Exception e) {
            LOGGER.error("Error initializing graph service at {}: {}", originalHome.getAbsolutePath(),
                    e.getMessage(), e);
            return false;
        }
    }

    private static File resolveGraphHome(IPEDSource source) {
        File[] candidates = new File[] {
                new File(source.getModuleDir(), GraphTask.DB_HOME_DIR),
                new File(source.getCaseDir(), IPEDSource.MODULE_DIR + "/" + GraphTask.DB_HOME_DIR),
                new File(source.getCaseDir(), GraphTask.DB_HOME_DIR),
                new File(source.getModuleDir(), GraphTask.DB_DATA_PATH),
                new File(source.getCaseDir(), IPEDSource.MODULE_DIR + "/" + GraphTask.DB_DATA_PATH),
                new File(source.getCaseDir(), GraphTask.DB_DATA_PATH) };

        for (File candidate : candidates) {
            LOGGER.debug("Checking graph home candidate {} for case {}", candidate.getAbsolutePath(),
                    source.getCaseDir());
            File normalized = normalizeGraphHome(candidate);
            if (normalized != null) {
                if (!candidate.equals(normalized)) {
                    LOGGER.debug("Resolved Neo4j data for {} using {}", source.getCaseDir(), normalized);
                }
                return normalized;
            }
        }

        return null;
    }

    private static File normalizeGraphHome(File candidate) {
        if (candidate == null) {
            return null;
        }

        File graphHome = candidate;
        if (GraphTask.DB_DATA_DIR.equals(graphHome.getName())) {
            graphHome = graphHome.getParentFile();
        }

        if (graphHome == null) {
            return null;
        }

        File dataDir = new File(graphHome, GraphTask.DB_DATA_DIR);
        return dataDir.exists() ? graphHome : null;
    }

    private static File copyToTempFolder(File dataDir) throws IOException {
        File tmpDb = new File(System.getProperty("java.io.tmpdir"), "iped-graph-" + dataDir.lastModified());
        IOUtil.copyDirectory(dataDir, new File(tmpDb, GraphTask.DB_DATA_DIR), true);
        return tmpDb;
    }

    /**
     * Get the initialized GraphService instance.
     */
    public static GraphService getGraphService() {
        return graphService;
    }

    /**
     * Check if the graph is available.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Shutdown the graph service.
     */
    public static synchronized void shutdown() {
        if (graphService != null) {
            try {
                graphService.stop();
            } catch (IOException e) {
                LOGGER.error("Error stopping graph service", e);
            }
            graphService = null;
            enabled = false;
            initialized = false;
        }
    }

    /**
     * Simple import listener for logging.
     */
    private static class LogImportListener implements ImportListener {
        @Override
        public void output(String line) {
            if (!line.trim().isEmpty()) {
                LOGGER.info("Graph import: {}", line.trim());
            }
        }
    }
}
