package iped.engine.webapi;

import java.io.Closeable;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Tracks HTTP requests for monitoring and debugging purposes.
 */
public class RequestTracker {

    private static final RequestTracker INSTANCE = new RequestTracker();
    private static final int MAX_HISTORY = 100;

    private final Map<Long, RequestInfo> activeRequests = new ConcurrentHashMap<>();
    private final Map<Long, RequestInfo> completedRequests = new ConcurrentHashMap<>();
    private final AtomicLong requestIdCounter = new AtomicLong(0);

    public static RequestTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Register a new request starting.
     * @return the request ID
     */
    public long startRequest(String method, String path, String queryString) {
        long id = requestIdCounter.incrementAndGet();
        RequestInfo info = new RequestInfo(id, method, path, queryString);
        activeRequests.put(id, info);
        return id;
    }

    /**
     * Mark a request as completed.
     */
    public void completeRequest(long id, int statusCode) {
        RequestInfo info = activeRequests.remove(id);
        if (info != null) {
            info.complete(statusCode);
            addToHistory(info);
        }
    }

    /**
     * Mark a request as failed.
     */
    public void failRequest(long id, String error) {
        RequestInfo info = activeRequests.remove(id);
        if (info != null) {
            info.fail(error);
            addToHistory(info);
        }
    }

    /**
     * Cancel/abort a request by closing the underlying connection.
     */
    public boolean cancelRequest(long id) {
        RequestInfo info = activeRequests.get(id);
        if (info != null) {
            info.cancel();
            // Close the underlying connection to force-terminate the request
            Closeable conn = info.getConnection();
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception e) {
                    // ignore close errors
                }
            }
            // Fallback: interrupt the thread so blocking calls can exit
            Thread t = info.getThread();
            if (t != null) {
                t.interrupt();
            }
            activeRequests.remove(id);
            info.markCancelled();
            addToHistory(info);
            return true;
        }
        return false;
    }

    /**
     * Get a request by ID (active or completed).
     */
    public RequestInfo getRequest(long id) {
        RequestInfo info = activeRequests.get(id);
        if (info == null) {
            info = completedRequests.get(id);
        }
        return info;
    }

    /**
     * Get all active requests.
     */
    public List<RequestInfo> getActiveRequests() {
        return new ArrayList<>(activeRequests.values());
    }

    /**
     * Get recent completed requests.
     */
    public List<RequestInfo> getCompletedRequests() {
        return completedRequests.values().stream()
                .sorted(Comparator.comparingLong(RequestInfo::getId).reversed())
                .collect(Collectors.toList());
    }

    private void addToHistory(RequestInfo info) {
        completedRequests.put(info.getId(), info);
        // Trim history if too large
        if (completedRequests.size() > MAX_HISTORY) {
            completedRequests.keySet().stream()
                    .sorted()
                    .limit(completedRequests.size() - MAX_HISTORY)
                    .forEach(completedRequests::remove);
        }
    }

    /**
     * Information about a single request.
     */
    public static class RequestInfo {
        private final long id;
        private final String method;
        private final String path;
        private final String queryString;
        private final Instant startTime;
        private final Thread thread;
        private volatile Closeable connection;
        private volatile Instant endTime;
        private volatile Status status;
        private volatile int statusCode;
        private volatile String error;
        private volatile boolean cancelled;

        public RequestInfo(long id, String method, String path, String queryString) {
            this.id = id;
            this.method = method;
            this.path = path;
            this.queryString = queryString;
            this.startTime = Instant.now();
            this.thread = Thread.currentThread();
            this.status = Status.IN_PROGRESS;
        }

        public void setConnection(Closeable connection) {
            this.connection = connection;
        }

        public Closeable getConnection() {
            return connection;
        }

        public void complete(int statusCode) {
            this.endTime = Instant.now();
            this.statusCode = statusCode;
            this.status = cancelled ? Status.CANCELLED : Status.COMPLETED;
        }

        public void fail(String error) {
            this.endTime = Instant.now();
            this.error = error;
            this.status = cancelled ? Status.CANCELLED : Status.FAILED;
        }

        public void cancel() {
            this.cancelled = true;
        }

        public void markCancelled() {
            this.endTime = Instant.now();
            this.status = Status.CANCELLED;
        }

        public long getId() { return id; }
        public String getMethod() { return method; }
        public String getPath() { return path; }
        public String getQueryString() { return queryString; }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        public Thread getThread() { return thread; }
        public Status getStatus() { return status; }
        public int getStatusCode() { return statusCode; }
        public String getError() { return error; }
        public boolean isCancelled() { return cancelled; }

        public long getDurationMs() {
            Instant end = endTime != null ? endTime : Instant.now();
            return Duration.between(startTime, end).toMillis();
        }

        public String getFullPath() {
            if (queryString != null && !queryString.isEmpty()) {
                return path + "?" + queryString;
            }
            return path;
        }

        @Override
        public String toString() {
            return String.format("[%d] %s %s - %s (%dms)", 
                    id, method, getFullPath(), status, getDurationMs());
        }
    }

    public enum Status {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
