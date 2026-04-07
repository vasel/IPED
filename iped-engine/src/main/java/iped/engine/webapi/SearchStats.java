package iped.engine.webapi;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds precomputed statistics (totals, category counts, bookmark counts) per source
 * and aggregated across all sources.
 */
public class SearchStats {
    private final Map<String, Integer> sourceTotals;
    private final Map<String, Map<String, Integer>> sourceCategoryCounts;
    private final Map<String, Map<String, Integer>> sourceBookmarkCounts;
    private final Map<String, Integer> categoryTotals;
    private final Map<String, Integer> bookmarkTotals;

    public SearchStats(Map<String, Integer> sourceTotals,
                       Map<String, Map<String, Integer>> sourceCategoryCounts,
                       Map<String, Map<String, Integer>> sourceBookmarkCounts,
                       Map<String, Integer> categoryTotals,
                       Map<String, Integer> bookmarkTotals) {
        this.sourceTotals = copy(sourceTotals);
        this.sourceCategoryCounts = deepCopy(sourceCategoryCounts);
        this.sourceBookmarkCounts = deepCopy(sourceBookmarkCounts);
        this.categoryTotals = copy(categoryTotals);
        this.bookmarkTotals = copy(bookmarkTotals);
    }

    public Map<String, Integer> getSourceTotals() {
        return sourceTotals;
    }

    public Map<String, Map<String, Integer>> getSourceCategoryCounts() {
        return sourceCategoryCounts;
    }

    public Map<String, Map<String, Integer>> getSourceBookmarkCounts() {
        return sourceBookmarkCounts;
    }

    public Map<String, Integer> getCategoryTotals() {
        return categoryTotals;
    }

    public Map<String, Integer> getBookmarkTotals() {
        return bookmarkTotals;
    }

    private Map<String, Integer> copy(Map<String, Integer> input) {
        return Collections.unmodifiableMap(new HashMap<>(input));
    }

    private Map<String, Map<String, Integer>> deepCopy(Map<String, Map<String, Integer>> input) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : input.entrySet()) {
            result.put(e.getKey(), copy(e.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }
}
