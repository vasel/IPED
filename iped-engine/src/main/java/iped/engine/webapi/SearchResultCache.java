package iped.engine.webapi;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.webapi.json.SourceToIDsJSON;

/**
 * LRU cache for search results keyed by (query + sourceID + sort + start + rows).
 * <p>
 * The cache lives entirely in memory with a configurable maximum size and TTL.
 * It is thread-safe (backed by Caffeine).
 * <p>
 * Configure via system properties:
 * <ul>
 *     <li>{@code iped.webapi.searchcache.maxsize} — max entries (default 256)</li>
 *     <li>{@code iped.webapi.searchcache.ttl} — seconds before eviction (default 60)</li>
 *     <li>{@code iped.webapi.searchcache.enabled} — false to disable (default true)</li>
 * </ul>
 */
public class SearchResultCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchResultCache.class);

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("iped.webapi.searchcache.enabled", "true"));

    private static final int MAX_SIZE = Integer.parseInt(
            System.getProperty("iped.webapi.searchcache.maxsize", "256"));

    private static final int TTL_SECONDS = Integer.parseInt(
            System.getProperty("iped.webapi.searchcache.ttl", "60"));

    private static final Cache<String, SourceToIDsJSON> CACHE;
    static {
        if (ENABLED) {
            CACHE = Caffeine.newBuilder()
                    .maximumSize(MAX_SIZE)
                    .expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS)
                    .recordStats()
                    .build();
            LOGGER.info("Search result cache enabled: maxSize={}, ttl={}s", MAX_SIZE, TTL_SECONDS);
        } else {
            CACHE = null;
            LOGGER.info("Search result cache disabled");
        }
    }

    public static boolean isEnabled() {
        return ENABLED && CACHE != null;
    }

    /**
     * Build the cache key from query parameters.
     */
    public static String key(String q, String sourceID, String sortField, String sortOrder, int start, int rows) {
        return q + "|" + sourceID + "|" + sortField + "|" + sortOrder + "|" + start + "|" + rows;
    }

    /**
     * Get a cached result, or null if not present / cache disabled.
     */
    public static SourceToIDsJSON get(String key) {
        if (!isEnabled()) return null;
        return CACHE.getIfPresent(key);
    }

    /**
     * Store a result in the cache.
     */
    public static void put(String key, SourceToIDsJSON result) {
        if (!isEnabled()) return;
        CACHE.put(key, result);
    }

    /**
     * Invalidate all entries (e.g. after bookmarks change or source reload).
     */
    public static void invalidateAll() {
        if (isEnabled()) {
            CACHE.invalidateAll();
        }
    }

    /**
     * Return human-readable stats for monitoring.
     */
    public static String statsString() {
        if (!isEnabled()) return "cache disabled";
        return CACHE.stats().toString();
    }
}
