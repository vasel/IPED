package iped.engine.webapi;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;

import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.TskCoreException;

import iped.engine.sleuthkit.SleuthkitInputStreamFactory;

import iped.data.IIPEDSource;
import iped.engine.data.IPEDSource;
import iped.engine.task.index.IndexItem;
import iped.utils.UTF8Properties;

/**
 * Interactive console for monitoring and managing HTTP requests.
 */
public class RequestMonitorConsole implements Runnable {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long SLOW_THRESHOLD_MS = 1000;

    // ANSI color codes
    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CLEAR_SCREEN = "\033[H\033[2J";

    private volatile boolean running = true;
    private volatile boolean watchMode = false;

    // Singleton instance for stdin sharing
    private static volatile RequestMonitorConsole instance;

    // Flag set by external threads to redirect the next readLine() to them.
    private final AtomicBoolean externalInputPending = new AtomicBoolean(false);

    // Queue used to deliver the line read by the console loop to the external
    // thread.
    private final SynchronousQueue<String> externalLineResponse = new SynchronousQueue<>();

    public static RequestMonitorConsole getInstance() {
        return instance;
    }

    /**
     * Called from any thread (e.g. HTTP request thread) that needs to read
     * a line from stdin while the console loop is running.
     * Sets a flag so the console loop forwards the next line here
     * instead of processing it as a command.
     *
     * @param prompt ignored (prompt is already printed by the caller)
     * @return the line read from stdin
     * @throws IOException if interrupted or stdin is closed
     */
    public String readLineFromConsole(String prompt) throws IOException {
        externalInputPending.set(true);
        try {
            // Wait for the console loop to read a line and forward it here
            String line = externalLineResponse.take();
            if ("__EOF__".equals(line)) {
                throw new IOException("stdin closed");
            }
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for console input", e);
        } finally {
            externalInputPending.set(false);
        }
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        instance = this;
        // Register the shared stdin reader so IndexItem doesn't create
        // a competing BufferedReader on System.in
        IndexItem.setConsoleLineReader(p -> readLineFromConsole(p));
        printHelp();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (running) {
                System.out.print("\niped-webapi> ");
                System.out.flush();
                String line = reader.readLine();

                if (line == null) {
                    // stdin closed — if an external thread is waiting, unblock it
                    if (externalInputPending.getAndSet(false)) {
                        try {
                            externalLineResponse.offer("__EOF__", 2, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    break;
                }

                // If an external thread is waiting for input, forward this line to it
                if (externalInputPending.get()) {
                    try {
                        boolean delivered = externalLineResponse.offer(line, 5, TimeUnit.SECONDS);
                        if (!delivered) {
                            // External thread probably died — reset and process as command
                            externalInputPending.set(false);
                        } else {
                            continue;
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                try {
                    processCommand(trimmed.toLowerCase(), trimmed, reader);
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Console error: " + e.getMessage());
        }
    }

    private void processCommand(String line, String originalLine, BufferedReader reader) throws Exception {
        String[] parts = line.split("\\s+");
        String cmd = parts[0];

        switch (cmd) {
            case "help":
            case "h":
            case "?":
                printHelp();
                break;

            case "active":
            case "a":
                listActiveRequests();
                break;

            case "history":
            case "hist":
                int limit = parts.length > 1 ? Integer.parseInt(parts[1]) : 20;
                listHistory(limit);
                break;

            case "status":
            case "s":
                printStatus();
                break;

            case "cancel":
            case "kill":
            case "k":
                if (parts.length < 2) {
                    System.out.println("Usage: cancel <request_id>  or  cancel all <seconds>");
                } else if ("all".equals(parts[1])) {
                    long thresholdSec = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
                    cancelAllSlow(thresholdSec, reader);
                } else {
                    cancelRequest(Long.parseLong(parts[1]));
                }
                break;

            case "info":
            case "i":
                if (parts.length < 2) {
                    System.out.println("Usage: info <request_id>");
                } else {
                    showRequestInfo(Long.parseLong(parts[1]));
                }
                break;

            case "where":
                if (parts.length < 2) {
                    System.out.println("Usage: where <request_id> [frames]");
                } else {
                    int frames = parts.length > 2 ? Integer.parseInt(parts[2]) : 20;
                    showRequestStack(Long.parseLong(parts[1]), frames);
                }
                break;

            case "clear":
            case "cls":
                // Clear screen (ANSI escape code)
                System.out.print("\033[H\033[2J");
                System.out.flush();
                break;

            case "watch":
            case "w":
            case "live":
            case "monitor":
                int watchLimit = parts.length > 1 ? Integer.parseInt(parts[1]) : 15;
                watchLoop(watchLimit);
                break;

            case "stats":
                StatsArgs statsArgs = parseStatsArgs(originalLine);
                printStats(statsArgs.top, statsArgs.sortByP95, statsArgs.methodFilter, statsArgs.pathFilter);
                break;

            case "sources":
            case "src":
                listSourcesInfo();
                break;

            case "reimage":
                if (parts.length < 4) {
                    System.out.println("Usage: reimage <sourceID> <imgId> <newPath>");
                    System.out.println("  Use 'sources' to list image IDs.");
                } else {
                    String reimageSrcId = parts[1];
                    long reimageImgId = Long.parseLong(parts[2]);
                    // Use originalLine to preserve path case
                    String[] origParts = originalLine.split("\\s+", 4);
                    String reimagePath = origParts.length >= 4 ? origParts[3].trim() : "";
                    reimageImagePath(reimageSrcId, reimageImgId, reimagePath, reader);
                }
                break;

            case "process":
                handleProcessCommand(originalLine);
                break;

            case "quit":
            case "exit":
            case "q":
                System.out.println("Use 'shutdown' to stop the server, or Ctrl+C.");
                break;

            case "shutdown":
            case "stop":
                shutdownServer(reader);
                break;

            default:
                System.out.println("Unknown command: " + cmd + ". Type 'help' for available commands.");
        }
    }

    private void printHelp() {
        System.out.println("\n=== IPED WebAPI Request Monitor ===");
        System.out.println("Commands:");
        System.out.println("  active, a          - List active (in-progress) requests");
        System.out.println("  history [n], hist  - Show last n completed requests (default: 20)");
        System.out.println("  status, s          - Show server status summary");
        System.out.println("  info <id>, i <id>  - Show details of a specific request");
        System.out.println("  where <id> [n]     - Show stack trace for a request (top n frames)");
        System.out.println("  cancel <id>, k <id>- Cancel/interrupt a stuck request");
        System.out.println("  cancel all [secs]  - Cancel all active requests (>secs seconds)");
        System.out.println("  watch [n], w, live - Live monitor with colors (press Enter to exit)");
        System.out.println("  stats [n] [p95|total] [METHOD] [PATH] - Show request stats");
        System.out.println("  sources, src       - List sources, index dirs, sleuth DB and image paths");
        System.out.println("  reimage <src> <imgId> <path> - Change an image path for a source");
        System.out.println("  process thumbnails [--force] <ext...> - Generate thumbnails in background");
        System.out.println("  process status     - Show background process status");
        System.out.println("  process watch      - Watch background process progress");
        System.out.println("  process cancel     - Cancel running background process");
        System.out.println("  shutdown, stop     - Stop the web server");
        System.out.println("  clear, cls         - Clear screen");
        System.out.println("  help, h, ?         - Show this help");
    }

    private void printStats(int top, boolean sortByP95, String methodFilter, String pathFilter) {
        List<RequestTracker.RequestInfo> active = RequestTracker.getInstance().getActiveRequests();
        List<RequestTracker.RequestInfo> history = RequestTracker.getInstance().getCompletedRequests();

        List<RequestTracker.RequestInfo> filteredHistory = new ArrayList<>();
        for (RequestTracker.RequestInfo req : history) {
            if (methodFilter != null && !methodFilter.equalsIgnoreCase(req.getMethod())) {
                continue;
            }
            if (pathFilter != null && !req.getPath().contains(pathFilter)) {
                continue;
            }
            filteredHistory.add(req);
        }

        if (filteredHistory.isEmpty()) {
            System.out.println("No request history.");
            return;
        }

        int ok = 0;
        int failed = 0;
        int cancelled = 0;
        List<Long> durations = new ArrayList<>();
        for (RequestTracker.RequestInfo req : filteredHistory) {
            durations.add(req.getDurationMs());
            switch (req.getStatus()) {
                case COMPLETED:
                    ok++;
                    break;
                case FAILED:
                    failed++;
                    break;
                case CANCELLED:
                    cancelled++;
                    break;
                default:
                    break;
            }
        }

        durations.sort(Long::compareTo);
        long totalMs = 0;
        for (Long d : durations) {
            totalMs += d;
        }

        long avg = totalMs / Math.max(1, durations.size());
        long p50 = percentile(durations, 50);
        long p95 = percentile(durations, 95);
        long p99 = percentile(durations, 99);
        long max = durations.get(durations.size() - 1);

        RequestTracker tracker = RequestTracker.getInstance();
        System.out.println("\n=== Request Stats ===");
        System.out.println("History size:  " + filteredHistory.size()
            + " (stored max " + tracker.getMaxHistory() + ", total " + tracker.getCompletedCount() + ")");
        System.out.println("Active:        " + active.size());
        System.out.println("Completed:     " + ok + " ok, " + failed + " failed, " + cancelled + " cancelled");
        System.out.println("Durations:     avg " + formatDuration(avg)
                + ", p50 " + formatDuration(p50)
                + ", p95 " + formatDuration(p95)
                + ", p99 " + formatDuration(p99)
                + ", max " + formatDuration(max));
        if (methodFilter != null || pathFilter != null) {
            System.out.println("Filter:        "
                    + (methodFilter != null ? methodFilter.toUpperCase() : "*")
                    + " " + (pathFilter != null ? pathFilter : "*"));
        }
        System.out.println("Sort:          " + (sortByP95 ? "p95" : "total"));

        class Agg {
            int count;
            long total;
            long max;
            int errors;
            List<Long> durations = new ArrayList<>();
        }

        Map<String, Agg> byEndpoint = new java.util.HashMap<>();
        for (RequestTracker.RequestInfo req : filteredHistory) {
            String key = req.getMethod() + " " + req.getPath();
            Agg agg = byEndpoint.computeIfAbsent(key, k -> new Agg());
            agg.count++;
            agg.total += req.getDurationMs();
            agg.max = Math.max(agg.max, req.getDurationMs());
            agg.durations.add(req.getDurationMs());
            if (req.getStatus() == RequestTracker.Status.FAILED) {
                agg.errors++;
            }
        }

        List<Map.Entry<String, Agg>> topList = byEndpoint.entrySet().stream()
                .sorted((a, b) -> {
                    long aKey = sortByP95 ? percentileSorted(a.getValue().durations, 95) : a.getValue().total;
                    long bKey = sortByP95 ? percentileSorted(b.getValue().durations, 95) : b.getValue().total;
                    return Long.compare(bKey, aKey);
                })
                .limit(Math.max(1, top))
                .collect(java.util.stream.Collectors.toList());

        System.out.println("\n--- Top endpoints by total time ---");
        System.out.println(String.format("%-5s %-10s %-10s %-10s %-10s %-6s %s",
                "CNT", "TOTAL", "AVG", "P95", "MAX", "ERR", "ENDPOINT"));
        for (Map.Entry<String, Agg> entry : topList) {
            Agg agg = entry.getValue();
            long avgMs = agg.total / Math.max(1, agg.count);
            long epP95 = percentileSorted(agg.durations, 95);
            System.out.println(String.format("%-5d %-10s %-10s %-10s %-10s %-6d %s",
                    agg.count,
                    formatDuration(agg.total),
                    formatDuration(avgMs),
                    formatDuration(epP95),
                    formatDuration(agg.max),
                    agg.errors,
                    entry.getKey()));
        }
    }

    private long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        if (percentile <= 0) {
            return sorted.get(0);
        }
        if (percentile >= 100) {
            return sorted.get(sorted.size() - 1);
        }
        double rank = (percentile / 100.0) * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted.get(low);
        }
        long lowVal = sorted.get(low);
        long highVal = sorted.get(high);
        double frac = rank - low;
        return (long) (lowVal + (highVal - lowVal) * frac);
    }

    private long percentileSorted(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> copy = new ArrayList<>(values);
        copy.sort(Long::compareTo);
        return percentile(copy, percentile);
    }

    private static class StatsArgs {
        final int top;
        final boolean sortByP95;
        final String methodFilter;
        final String pathFilter;

        StatsArgs(int top, boolean sortByP95, String methodFilter, String pathFilter) {
            this.top = top;
            this.sortByP95 = sortByP95;
            this.methodFilter = methodFilter;
            this.pathFilter = pathFilter;
        }
    }

    private StatsArgs parseStatsArgs(String originalLine) {
        String[] tokens = originalLine.trim().split("\\s+");
        int top = 10;
        boolean sortByP95 = false;
        String methodFilter = null;
        String pathFilter = null;

        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.matches("\\d+")) {
                top = Integer.parseInt(token);
                continue;
            }
            if ("p95".equalsIgnoreCase(token)) {
                sortByP95 = true;
                continue;
            }
            if ("total".equalsIgnoreCase(token)) {
                sortByP95 = false;
                continue;
            }
            if (isHttpMethod(token)) {
                methodFilter = token.toUpperCase();
                continue;
            }
            if (pathFilter == null) {
                pathFilter = token;
            }
        }
        return new StatsArgs(top, sortByP95, methodFilter, pathFilter);
    }

    private boolean isHttpMethod(String token) {
        String m = token.toUpperCase();
        return "GET".equals(m) || "POST".equals(m) || "PUT".equals(m) || "DELETE".equals(m)
                || "PATCH".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m);
    }

    private void listActiveRequests() {
        List<RequestTracker.RequestInfo> active = RequestTracker.getInstance().getActiveRequests();

        if (active.isEmpty()) {
            System.out.println("No active requests.");
            return;
        }

        System.out.println("\n=== Active Requests (" + active.size() + ") ===");
        System.out.println(String.format("%-6s %-8s %-10s %-30s %s",
                "ID", "METHOD", "DURATION", "THREAD", "PATH"));
        System.out.println("-".repeat(120));

        for (RequestTracker.RequestInfo req : active) {
            String duration = formatDuration(req.getDurationMs());
            String threadName = req.getThread().getName();

            // Highlight long-running requests
            String marker = req.getDurationMs() > SLOW_THRESHOLD_MS ? " [SLOW!]" : "";

            System.out.println(String.format("%-6d %-8s %-10s %-30s %s%s",
                    req.getId(), req.getMethod(), duration, threadName, req.getFullPath(), marker));
        }
    }

    private void listHistory(int limit) {
        List<RequestTracker.RequestInfo> history = RequestTracker.getInstance().getCompletedRequests();

        if (history.isEmpty()) {
            System.out.println("No request history.");
            return;
        }

        // Sort by duration descending (slowest first)
        List<RequestTracker.RequestInfo> sorted = new ArrayList<>(history);
        sorted.sort((a, b) -> Long.compare(b.getDurationMs(), a.getDurationMs()));

        int count = Math.min(limit, sorted.size());
        long total = RequestTracker.getInstance().getCompletedCount();
        System.out.println("\n=== Recent Requests (top " + count + " slowest of " + total + ") ===");
        System.out.println(String.format("%-6s %-8s %-10s %-10s %-6s %s",
                "ID", "METHOD", "STATUS", "DURATION", "HTTP", "PATH"));
        System.out.println("-".repeat(120));

        for (int i = 0; i < count; i++) {
            RequestTracker.RequestInfo req = sorted.get(i);
            String duration = formatDuration(req.getDurationMs());
            String status = req.getStatus().toString();
            String httpCode = req.getStatusCode() > 0 ? String.valueOf(req.getStatusCode()) : "-";

            System.out.println(String.format("%-6d %-8s %-10s %-10s %-6s %s",
                    req.getId(), req.getMethod(), status, duration, httpCode, req.getFullPath()));
        }
    }

    private void printStatus() {
        List<RequestTracker.RequestInfo> active = RequestTracker.getInstance().getActiveRequests();
        List<RequestTracker.RequestInfo> history = RequestTracker.getInstance().getCompletedRequests();

        long slowRequests = active.stream()
                .filter(r -> r.getDurationMs() > SLOW_THRESHOLD_MS)
                .count();

        long failedRecent = history.stream()
                .limit(50)
                .filter(r -> r.getStatus() == RequestTracker.Status.FAILED)
                .count();

        System.out.println("\n=== Server Status ===");
        System.out.println("Active requests:     " + active.size());
        System.out.println("Slow requests (>30s):" + slowRequests);
        System.out.println("Recent failures:     " + failedRecent + " (of last 50)");
        System.out.println("Total in history:    " + RequestTracker.getInstance().getCompletedCount()
            + " (stored " + history.size() + ")");

        if (slowRequests > 0) {
            System.out.println(
                    "\n[WARNING] Slow requests (>" + SLOW_THRESHOLD_MS + "ms) detected! Use 'active' to see details.");
        }
    }

    private void showRequestInfo(long id) {
        RequestTracker.RequestInfo req = RequestTracker.getInstance().getRequest(id);

        if (req == null) {
            System.out.println("Request not found: " + id);
            return;
        }

        System.out.println("\n=== Request #" + id + " ===");
        System.out.println("Method:     " + req.getMethod());
        System.out.println("Path:       " + req.getPath());
        System.out.println("Query:      " + (req.getQueryString() != null ? req.getQueryString() : "(none)"));
        if (req.getClientIp() != null) {
            System.out.println("Client IP:  " + req.getClientIp());
        }
        System.out.println("Status:     " + req.getStatus());
        System.out.println("HTTP Code:  " + (req.getStatusCode() > 0 ? req.getStatusCode() : "-"));
        System.out.println(
                "Started:    " + TIME_FORMAT.format(req.getStartTime().atZone(java.time.ZoneId.systemDefault())));
        System.out.println("Duration:   " + formatDuration(req.getDurationMs()));

        if (req.getRequestBody() != null) {
            System.out.println("Body (captured):");
            String body = req.getRequestBody();
            for (String line : body.split("\r?\n")) {
                System.out.println("  " + line);
            }
            if (req.isRequestBodyTruncated()) {
                System.out.println("  ... (truncated) ...");
            }
        }

        if (req.getThread() != null) {
            Thread t = req.getThread();
            System.out.println("Thread:     " + t.getName() + " [" + t.getState() + "]");
            StackTraceElement[] stack = t.getStackTrace();
            if (stack != null && stack.length > 0) {
                System.out.println("At:         " + stack[0].toString());
            }
        }

        if (req.getError() != null) {
            System.out.println("Error:      " + req.getError());
        }

        if (req.isCancelled()) {
            System.out.println("[CANCELLED]");
        }

        java.util.Map<String, Long> phases = req.getPhaseDurationsMs();
        if (!phases.isEmpty()) {
            System.out.println("\nPhase timings (sorted by duration):");
            long total = Math.max(1, req.getDurationMs());
            phases.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(entry -> {
                        long ms = entry.getValue();
                        long pct = Math.round((ms * 100.0) / total);
                        System.out.println(String.format("  %-20s %7d ms  (%2d%%)", entry.getKey(), ms, pct));
                    });
        }
    }

    private void showRequestStack(long id, int maxFrames) {
        RequestTracker.RequestInfo req = RequestTracker.getInstance().getRequest(id);
        if (req == null) {
            System.out.println("Request not found: " + id);
            return;
        }
        Thread t = req.getThread();
        if (t == null) {
            System.out.println("No thread info for request: " + id);
            return;
        }
        StackTraceElement[] stack = t.getStackTrace();
        System.out.println("\n=== Stack for Request #" + id + " ===");
        System.out.println("Thread: " + t.getName() + " [" + t.getState() + "]");
        int limit = Math.min(Math.max(1, maxFrames), stack.length);
        for (int i = 0; i < limit; i++) {
            System.out.println("  at " + stack[i]);
        }
    }

    private void cancelRequest(long id) {
        RequestTracker.RequestInfo req = RequestTracker.getInstance().getRequest(id);

        if (req == null) {
            System.out.println("Request not found: " + id);
            return;
        }

        if (req.getStatus() != RequestTracker.Status.IN_PROGRESS) {
            System.out.println("Request is not active (status: " + req.getStatus() + ")");
            return;
        }

        boolean cancelled = RequestTracker.getInstance().cancelRequest(id);
        if (cancelled) {
            System.out.println("Request #" + id + " has been interrupted.");
            System.out.println("Thread: " + req.getThread().getName());
        } else {
            System.out.println("Could not interrupt request #" + id);
        }
    }

    private void cancelAllSlow(long thresholdSec, BufferedReader reader) throws Exception {
        long thresholdMs = thresholdSec * 1000;
        List<RequestTracker.RequestInfo> active = RequestTracker.getInstance().getActiveRequests();

        List<RequestTracker.RequestInfo> targets = new java.util.ArrayList<>();
        for (RequestTracker.RequestInfo req : active) {
            if (req.getDurationMs() > thresholdMs) {
                targets.add(req);
            }
        }

        if (targets.isEmpty()) {
            System.out.println("No active requests above " + thresholdSec + "s.");
            return;
        }

        System.out.println("\nRequests to cancel (" + targets.size() + "):");
        for (RequestTracker.RequestInfo req : targets) {
            System.out.println(RED + "  #" + req.getId() + " " + req.getMethod() + " "
                    + truncate(req.getFullPath(), 50) + "  " + formatDuration(req.getDurationMs()) + RESET);
        }
        System.out.print("\nConfirm cancel " + targets.size() + " request(s)? (y/N): ");
        String answer = reader.readLine();
        if (answer != null && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
            int cancelled = 0;
            for (RequestTracker.RequestInfo req : targets) {
                if (RequestTracker.getInstance().cancelRequest(req.getId())) {
                    cancelled++;
                }
            }
            System.out.println("Cancelled " + cancelled + " of " + targets.size() + " requests.");
        } else {
            System.out.println("Aborted.");
        }
    }

    private void shutdownServer(BufferedReader reader) throws Exception {
        List<RequestTracker.RequestInfo> active = RequestTracker.getInstance().getActiveRequests();
        if (!active.isEmpty()) {
            System.out.println(YELLOW + "WARNING: " + active.size() + " request(s) still active." + RESET);
        }
        System.out.print("Are you sure you want to stop the server? (y/N): ");
        String answer = reader.readLine();
        if (answer != null && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
            System.out.println("Shutting down...");

            // Cancel all active requests
            for (RequestTracker.RequestInfo req : active) {
                RequestTracker.getInstance().cancelRequest(req.getId());
            }

            // Stop the Grizzly HTTP server
            org.glassfish.grizzly.http.server.HttpServer server = Main.httpServer;
            if (server != null) {
                server.shutdownNow();
            }

            // Close sources
            if (Sources.multiSource != null) {
                try {
                    Sources.multiSource.close();
                } catch (Exception e) {
                    // ignore
                }
            }

            running = false;
            System.out.println("Server stopped.");

            // Exit the JVM
            System.exit(0);
        } else {
            System.out.println("Aborted.");
        }
    }

    private static final String NEW_DATASOURCE_PATH_FILE = "data/newDataSourceLocations.txt";

    private void listSourcesInfo() {
        if (Sources.multiSource == null) {
            System.out.println("No sources loaded.");
            return;
        }

        List<IPEDSource> sources = Sources.multiSource.getAtomicSources();
        System.out.println("\n=== Sources (" + sources.size() + ") ===");

        // Proactively validate data source paths and prompt in-console when missing.
        runDataSourcePrecheck(sources);

        for (IPEDSource source : sources) {
            int srcId = source.getSourceId();
            String externalId = Sources.sourceIntToString != null
                    ? Sources.sourceIntToString.getOrDefault(srcId, "?")
                    : "?";

            File caseDir = source.getCaseDir();
            File moduleDir = source.getModuleDir();
            File indexDir = source.getIndex();
            String baseDir = moduleDir.getParentFile() != null
                    ? moduleDir.getParentFile().toPath().toString()
                    : caseDir.toString();

            System.out.println("\n--- Source #" + srcId + " (id=\"" + externalId + "\") ---");
            System.out.println("  Case dir:    " + caseDir);
            System.out.println("  Module dir:  " + moduleDir);
            System.out.println("  Index dir:   " + indexDir);
            System.out.println("  Base dir:    " + baseDir);
            System.out.println("  Items:       " + source.getTotalItems());
            System.out.println("  Total size:  " + formatSizeMB(computeTotalSizeMB(source)));

            // Sleuthkit case info
            File expectedSleuthFile = new File(caseDir, IPEDSource.SLEUTH_DB);
            SleuthkitCase sleuthCase = source.getSleuthCase();
            if (sleuthCase != null) {
                String sleuthFile = sleuthCase.getDbDirPath() + "/" + IPEDSource.SLEUTH_DB;
                System.out.println("  Sleuth DB:   " + GREEN + sleuthFile + RESET);
                try {
                    Map<Long, List<String>> imgPaths = sleuthCase.getImagePaths();
                    if (imgPaths.isEmpty()) {
                        System.out.println("  Image paths: (none)");
                    } else {
                        System.out.println("  Image paths:");
                        for (Map.Entry<Long, List<String>> entry : imgPaths.entrySet()) {
                            long imgId = entry.getKey();
                            List<String> paths = entry.getValue();
                            for (String p : paths) {
                                boolean exists = new File(p).exists();
                                String flag = exists ? GREEN + "[OK]" + RESET : RED + "[NOT FOUND]" + RESET;
                                System.out.println("    imgId=" + imgId + "  " + flag + " " + p);
                            }
                        }
                    }
                } catch (TskCoreException e) {
                    System.out.println("  Image paths: error - " + e.getMessage());
                }
            } else {
                if (expectedSleuthFile.exists()) {
                    System.out.println("  Sleuth DB:   " + YELLOW + expectedSleuthFile + RESET
                            + RED + " (file exists but not loaded by source)" + RESET);
                    // Try to open it temporarily to show image paths
                    SleuthkitCase tmpCase = null;
                    try {
                        tmpCase = SleuthkitInputStreamFactory.openSleuthkitCase(
                                expectedSleuthFile.getAbsolutePath());
                        Map<Long, List<String>> imgPaths = tmpCase.getImagePaths();
                        if (imgPaths.isEmpty()) {
                            System.out.println("  Image paths: (none)");
                        } else {
                            System.out.println("  Image paths:");
                            for (Map.Entry<Long, List<String>> entry : imgPaths.entrySet()) {
                                long imgId = entry.getKey();
                                List<String> paths = entry.getValue();
                                for (String p : paths) {
                                    boolean exists = new File(p).exists();
                                    String flag = exists ? GREEN + "[OK]" + RESET : RED + "[NOT FOUND]" + RESET;
                                    System.out.println("    imgId=" + imgId + "  " + flag + " " + p);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        System.out.println("  Image paths: " + RED + "could not open sleuth.db — "
                                + ex.getMessage() + RESET);
                    } finally {
                        if (tmpCase != null) {
                            try {
                                tmpCase.close();
                            } catch (Exception ignore) {
                            }
                        }
                    }
                } else {
                    System.out.println("  Sleuth DB:   " + YELLOW + expectedSleuthFile + RESET
                            + RED + " (file not found)" + RESET);
                }
            }

            // Check for relocated data source paths
            File relocFile = new File(moduleDir, NEW_DATASOURCE_PATH_FILE);
            if (relocFile.exists()) {
                try {
                    UTF8Properties props = new UTF8Properties();
                    props.load(relocFile);
                    if (!props.isEmpty()) {
                        System.out.println("  Relocated paths (" + NEW_DATASOURCE_PATH_FILE + "):");
                        for (String key : props.stringPropertyNames()) {
                            String newPath = props.getProperty(key);
                            System.out.println("    " + key);
                            System.out.println("      -> " + newPath);
                        }
                    } else {
                        System.out.println("  Relocated paths: (none)");
                    }
                } catch (Exception e) {
                    System.out.println("  Relocated paths: error reading file - " + e.getMessage());
                }
            } else {
                System.out.println("  Relocated paths: (file not found)");
            }
        }
    }

    private void runDataSourcePrecheck(List<IPEDSource> sources) {
        if (sources.isEmpty()) {
            return;
        }

        // Ensure console prompts are enabled for missing paths.
        IndexItem.setUseConsoleForMissingDataSources(true);

        System.out.println("\nChecking data sources (will ask for new paths if missing)...");
        long totalStart = System.currentTimeMillis();
        for (IPEDSource source : sources) {
            String externalId = Sources.sourceIntToString != null
                    ? Sources.sourceIntToString.getOrDefault(source.getSourceId(), "?")
                    : "?";
            long start = System.currentTimeMillis();
            try {
                source.precheckDataSources();
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("  [" + externalId + "] data sources OK (" + formatDuration(elapsed) + ")");
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("  [" + externalId + "] ERROR after " + formatDuration(elapsed) + ": " + e.getMessage());
            }
        }
        long totalElapsed = System.currentTimeMillis() - totalStart;
        System.out.println("Finished data source check in " + formatDuration(totalElapsed));
    }

    private void reimageImagePath(String sourceExternalId, long imgId, String newPath, BufferedReader reader) {
        try {
            // Find the source by external ID
            Integer srcIdx = Sources.sourceStringToInt != null ? Sources.sourceStringToInt.get(sourceExternalId) : null;
            if (srcIdx == null) {
                System.out.println("Source not found: " + sourceExternalId);
                return;
            }
            IPEDSource source = Sources.multiSource.getAtomicSources().get(srcIdx);
            SleuthkitCase sleuthCase = source.getSleuthCase();
            if (sleuthCase == null) {
                System.out.println("No SleuthkitCase for this source.");
                return;
            }

            Map<Long, List<String>> imgPaths = sleuthCase.getImagePaths();
            List<String> currentPaths = imgPaths.get(imgId);
            if (currentPaths == null) {
                System.out.println("Image ID " + imgId + " not found. Available IDs: " + imgPaths.keySet());
                return;
            }

            File newFile = new File(newPath);
            if (!newFile.exists()) {
                System.out.println(YELLOW + "WARNING: File does not exist: " + newPath + RESET);
            }

            System.out.println("\nSource: " + sourceExternalId + "  Image ID: " + imgId);
            System.out.println("Current path(s):");
            for (String p : currentPaths) {
                boolean exists = new File(p).exists();
                String flag = exists ? GREEN + "[OK]" + RESET : RED + "[NOT FOUND]" + RESET;
                System.out.println("  " + flag + " " + p);
            }
            System.out.println("New path: " + newPath);

            // Handle multi-fragment images (e.g., .E01, .E02, ...)
            ArrayList<String> newPaths = new ArrayList<>();
            if (currentPaths.size() == 1) {
                newPaths.add(newFile.getAbsolutePath());
            } else {
                // For split images, compute the other fragments based on extension pattern
                String basePath = newFile.getAbsolutePath();
                if (basePath.contains(".")) {
                    basePath = basePath.substring(0, basePath.lastIndexOf('.'));
                }
                System.out.println("Multi-fragment image (" + currentPaths.size() + " parts). Computing fragments...");
                for (String origPath : currentPaths) {
                    String ext = origPath.substring(origPath.lastIndexOf('.'));
                    String fragmentPath = basePath + ext;
                    boolean exists = new File(fragmentPath).exists();
                    String flag = exists ? GREEN + "[OK]" + RESET : RED + "[MISSING]" + RESET;
                    System.out.println("  " + flag + " " + fragmentPath);
                    newPaths.add(fragmentPath);
                }
            }

            System.out.print("Confirm change? (y/N): ");
            String answer = reader.readLine();
            if (answer != null && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
                sleuthCase.setImagePaths(imgId, newPaths);
                System.out.println(GREEN + "Image path updated successfully." + RESET);
            } else {
                System.out.println("Aborted.");
            }

        } catch (TskCoreException e) {
            System.out.println(RED + "SleuthKit error: " + e.getMessage() + RESET);
        } catch (Exception e) {
            System.out.println(RED + "Error: " + e.getMessage() + RESET);
        }
    }

    private void handleProcessCommand(String originalLine) {
        String[] tokens = originalLine.trim().split("\\s+");
        if (tokens.length < 2) {
            printProcessHelp();
            return;
        }

        String subCmd = tokens[1].toLowerCase();
        switch (subCmd) {
            case "status":
                printBackgroundProcessStatus();
                break;

            case "watch":
                watchBackgroundProcess();
                break;

            case "cancel":
                if (ThumbnailProcessor.isBusy()) {
                    ThumbnailProcessor.cancelRunning();
                    System.out.println(YELLOW + "Cancel signal sent." + RESET);
                } else {
                    System.out.println("No background process is running.");
                }
                break;

            case "thumbnails":
            case "thumbs":
                startThumbnailProcess(tokens);
                break;

            default:
                System.out.println("Unknown process subcommand: " + subCmd);
                printProcessHelp();
        }
    }

    private void startThumbnailProcess(String[] tokens) {
        if (ThumbnailProcessor.isBusy()) {
            System.out.println(RED + "A background process is already running. Use 'process cancel' first." + RESET);
            return;
        }

        boolean force = false;
        java.util.Set<String> extensions = new java.util.LinkedHashSet<String>();
        java.util.Set<String> supported = ThumbnailProcessor.getSupportedExtensions();

        for (int i = 2; i < tokens.length; i++) {
            String tok = tokens[i].toLowerCase();
            if ("--force".equals(tok) || "-force".equals(tok)) {
                force = true;
            } else if ("--help".equals(tok) || "-h".equals(tok) || "help".equals(tok)) {
                ThumbnailProcessor.printHelp();
                return;
            } else if (supported.contains(tok)) {
                extensions.add(tok);
            } else {
                System.out.println(YELLOW + "Unsupported extension ignored: " + tok + RESET);
            }
        }

        if (extensions.isEmpty()) {
            ThumbnailProcessor.printHelp();
            return;
        }

        ThumbnailProcessor processor = new ThumbnailProcessor(extensions, force);
        Thread thread = new Thread(processor, "ThumbnailProcessor");
        thread.setDaemon(true);
        thread.start();

        System.out.println("Thumbnail generation started in background.");
        System.out.println("Use 'process status' or 'process watch' to follow progress.");
    }

    private void printBackgroundProcessStatus() {
        ThumbnailProcessor.StatusSnapshot snapshot = ThumbnailProcessor.getStatusSnapshot();
        if (snapshot == null) {
            System.out.println("No background process status available.");
            return;
        }

        System.out.println("\n=== Background Process Status ===");
        System.out.println(snapshot.summaryLine());
    }

    private void watchBackgroundProcess() {
        System.out.println("Watching background process (press Enter to stop watching)...");
        try {
            while (true) {
                ThumbnailProcessor.StatusSnapshot snapshot = ThumbnailProcessor.getStatusSnapshot();
                System.out.print(CLEAR_SCREEN);
                System.out.flush();

                System.out.println(BOLD + "=== Background Process Watch ===" + RESET);
                if (snapshot == null) {
                    System.out.println("No background process status available.");
                    break;
                }

                System.out.println("State:      " + snapshot.getMessage());
                System.out.println("Running:    " + snapshot.isRunning());
                System.out.println("Source:     " + (snapshot.getCurrentSource() != null ? snapshot.getCurrentSource() : "-"));
                System.out.println("Item:       " + (snapshot.getCurrentItem() != null ? snapshot.getCurrentItem() : "-"));
                System.out.println("Extensions: " + snapshot.getExtensions());
                System.out.println("Force:      " + snapshot.isForce());
                System.out.println("Matched:    " + snapshot.getMatched());
                System.out.println("Generated:  " + snapshot.getGenerated());
                System.out.println("Skipped:    " + snapshot.getSkipped());
                System.out.println("Errors:     " + snapshot.getErrors());
                System.out.println("Elapsed:    " + formatDuration(snapshot.getElapsedMs()));
                System.out.println(DIM + "Updated:    " + java.time.Instant.ofEpochMilli(snapshot.getUpdatedAt()) + RESET);
                System.out.println();
                System.out.println(DIM + "Press Enter to exit watch mode." + RESET);

                if (!snapshot.isRunning()) {
                    break;
                }

                Thread.sleep(1000);
                if (System.in.available() > 0) {
                    while (System.in.available() > 0) {
                        System.in.read();
                    }
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("Watch error: " + e.getMessage());
        } finally {
            System.out.println("\nExited background watch.");
        }
    }

    private void printProcessHelp() {
        System.out.println("Usage: process <subcommand> [args...]");
        System.out.println("  process thumbnails [--force|-force] <ext...>");
        System.out.println("  process status");
        System.out.println("  process watch");
        System.out.println("  process cancel");
    }

    private void watchLoop(int historyLimit) {
        System.out.println("Entering live monitor (press Enter to exit)...");
        watchMode = true;

        try {
            while (watchMode && running) {
                // Clear screen
                System.out.print(CLEAR_SCREEN);
                System.out.flush();

                List<RequestTracker.RequestInfo> active = RequestTracker.getInstance().getActiveRequests();
                List<RequestTracker.RequestInfo> history = RequestTracker.getInstance().getCompletedRequests();

                // Header
                System.out.println(BOLD + "=== IPED WebAPI Live Monitor === " + RESET
                        + DIM + "(press Enter to exit)" + RESET);
                System.out.println(DIM + "Updated: "
                        + TIME_FORMAT.format(java.time.LocalTime.now()) + RESET);
                System.out.println();

                // Active requests in GREEN
                System.out.println(BOLD + GREEN + "--- Active Requests (" + active.size() + ") ---" + RESET);
                if (active.isEmpty()) {
                    System.out.println(DIM + "  (none)" + RESET);
                } else {
                    System.out.println(GREEN + String.format("  %-6s %-7s %-10s %-30s %s",
                            "ID", "METHOD", "DURATION", "THREAD", "PATH") + RESET);
                    for (RequestTracker.RequestInfo req : active) {
                        String duration = formatDuration(req.getDurationMs());
                        String threadName = req.getThread().getName();
                        boolean slow = req.getDurationMs() > SLOW_THRESHOLD_MS;

                        String color = slow ? RED + BOLD : GREEN;
                        String suffix = slow ? " [SLOW!]" : "";

                        System.out.println(color + String.format("  %-6d %-7s %-10s %-30s %s%s",
                                req.getId(), req.getMethod(), duration, threadName, req.getFullPath(), suffix) + RESET);
                    }
                }
                System.out.println();

                // Recent history
                int count = Math.min(historyLimit, history.size());
                long total = RequestTracker.getInstance().getCompletedCount();
                System.out.println(BOLD + "--- Recent Requests (last " + count + " of " + total + ") ---" + RESET);
                if (history.isEmpty()) {
                    System.out.println(DIM + "  (none)" + RESET);
                } else {
                    System.out.println(DIM + String.format("  %-6s %-7s %-10s %-10s %-6s %s",
                            "ID", "METHOD", "STATUS", "DURATION", "HTTP", "PATH") + RESET);
                    for (int i = 0; i < count; i++) {
                        RequestTracker.RequestInfo req = history.get(i);
                        String duration = formatDuration(req.getDurationMs());
                        String status = req.getStatus().toString();
                        String httpCode = req.getStatusCode() > 0 ? String.valueOf(req.getStatusCode()) : "-";

                        boolean isError = req.getStatus() == RequestTracker.Status.FAILED
                                || req.getStatus() == RequestTracker.Status.CANCELLED
                                || req.getStatusCode() >= 500;
                        boolean isSlow = req.getDurationMs() > SLOW_THRESHOLD_MS;

                        String color;
                        if (isError) {
                            color = RED;
                        } else if (isSlow) {
                            color = YELLOW;
                        } else {
                            color = ""; // default terminal color (white)
                        }

                        String line = String.format("  %-6d %-7s %-10s %-10s %-6s %s",
                                req.getId(), req.getMethod(), status, duration, httpCode, req.getFullPath());
                        if (!color.isEmpty()) {
                            System.out.println(color + line + RESET);
                        } else {
                            System.out.println(line);
                        }
                    }
                }

                // Summary line
                long slowActive = active.stream().filter(r -> r.getDurationMs() > SLOW_THRESHOLD_MS).count();
                long failedRecent = history.stream().limit(50)
                        .filter(r -> r.getStatus() == RequestTracker.Status.FAILED).count();
                System.out.println();
                System.out.print(DIM + "Active: " + RESET + BOLD + active.size() + RESET);
                if (slowActive > 0) {
                    System.out.print("  " + RED + BOLD + "Slow: " + slowActive + RESET);
                }
                if (failedRecent > 0) {
                    System.out.print("  " + RED + "Errors(50): " + failedRecent + RESET);
                }
                System.out.print("  " + DIM + "History: " + RequestTracker.getInstance().getCompletedCount() + RESET);
                System.out.println();

                // Check if user pressed Enter to exit
                Thread.sleep(2000);
                if (System.in.available() > 0) {
                    // Consume the input
                    while (System.in.available() > 0) {
                        System.in.read();
                    }
                    watchMode = false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("Watch error: " + e.getMessage());
        } finally {
            watchMode = false;
            System.out.println("\nExited live monitor.");
        }
    }

    private String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60000) {
            return String.format("%.1fs", ms / 1000.0);
        } else {
            long minutes = ms / 60000;
            long seconds = (ms % 60000) / 1000;
            return String.format("%dm%ds", minutes, seconds);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null)
            return "";
        if (s.length() <= maxLen)
            return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Computes total file size in MB by iterating NumericDocValues for the "size"
     * field. Very fast - no stored fields are loaded.
     */
    private double computeTotalSizeMB(IPEDSource source) {
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

    private String formatSizeMB(double mb) {
        if (mb >= 1024) {
            return String.format("%.2f GB", mb / 1024.0);
        }
        return String.format("%.2f MB", mb);
    }
}
