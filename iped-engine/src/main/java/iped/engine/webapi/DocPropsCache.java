package iped.engine.webapi;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LRU cache for the (immutable) stored-field properties of a Lucene document,
 * keyed by ({@code sourceID}, {@code id}).
 * <p>
 * Bookmarks and selection are <b>not</b> cached here because they change at
 * runtime; callers must always rebuild those parts from the live source.
 * <p>
 * Configure via system properties:
 * <ul>
 *     <li>{@code iped.webapi.docpropscache.enabled} — false to disable (default true)</li>
 *     <li>{@code iped.webapi.docpropscache.maxsize} — max entries (default 50000)</li>
 *     <li>{@code iped.webapi.docpropscache.ttl} — seconds before eviction (default 300)</li>
 * </ul>
 */
public class DocPropsCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocPropsCache.class);

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("iped.webapi.docpropscache.enabled", "true"));

    private static final int MAX_SIZE = Integer.parseInt(
            System.getProperty("iped.webapi.docpropscache.maxsize", "50000"));

    private static final int TTL_SECONDS = Integer.parseInt(
            System.getProperty("iped.webapi.docpropscache.ttl", "300"));

    /** Cached value: properties map + lucene id. */
    public static final class Entry {
        public final int luceneId;
        public final Map<String, String[]> properties;

        public Entry(int luceneId, Map<String, String[]> properties) {
            this.luceneId = luceneId;
            this.properties = properties;
        }
    }

    private static final Cache<String, Entry> CACHE;
    static {
        if (ENABLED) {
            CACHE = Caffeine.newBuilder()
                    .maximumSize(MAX_SIZE)
                    .expireAfterAccess(TTL_SECONDS, TimeUnit.SECONDS)
                    .recordStats()
                    .build();
            LOGGER.info("Doc props cache enabled: maxSize={}, ttl={}s", MAX_SIZE, TTL_SECONDS);
        } else {
            CACHE = null;
            LOGGER.info("Doc props cache disabled");
        }
    }

    public static boolean isEnabled() {
        return ENABLED && CACHE != null;
    }

    private static String key(String sourceID, int id) {
        return sourceID + "#" + id;
    }

    public static Entry get(String sourceID, int id) {
        if (!isEnabled()) return null;
        return CACHE.getIfPresent(key(sourceID, id));
    }

    public static void put(String sourceID, int id, Entry entry) {
        if (!isEnabled()) return;
        CACHE.put(key(sourceID, id), entry);
    }

    public static void invalidateAll() {
        if (isEnabled()) {
            CACHE.invalidateAll();
        }
    }

    public static String statsString() {
        if (!isEnabled()) return "cache disabled";
        return CACHE.stats().toString();
    }
}
