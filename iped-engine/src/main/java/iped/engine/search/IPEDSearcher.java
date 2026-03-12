/*
 * Copyright 2012-2014, Luis Filipe da Cruz Nassif
 * 
 * This file is part of Indexador e Processador de Evidências Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package iped.engine.search;

import java.io.IOException;
import java.io.InterruptedIOException;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.FieldDoc;

import iped.data.IItemId;
import iped.engine.data.ItemId;

import iped.engine.data.IPEDMultiSource;
import iped.engine.data.IPEDSource;
import iped.engine.lucene.NoScoringCollector;
import iped.engine.task.index.IndexItem;
import iped.exception.ParseException;
import iped.exception.QueryNodeException;
import iped.search.IIPEDSearcher;
import iped.search.SearchResult;

public class IPEDSearcher implements IIPEDSearcher {

    public static final int MAX_SIZE_TO_SCORE = 1000000;

    IPEDSource ipedCase;
    Query query;
    boolean treeQuery, noScore, rewriteQuery = true;
    NoScoringCollector collector;
    Sort sort;

    private volatile boolean canceled;

    public IPEDSearcher(IPEDSource ipedCase) {
        this.ipedCase = ipedCase;
    }

    public IPEDSearcher(IPEDSource ipedCase, Query query) {
        this.ipedCase = ipedCase;
        this.query = query;
    }

    public IPEDSearcher(IPEDSource ipedCase, String query) {
        this.ipedCase = ipedCase;
        setQuery(query);
    }

    public IPEDSearcher(IPEDSource ipedCase, Query query, String... sort) {
        this.ipedCase = ipedCase;
        this.query = query;
        setSorting(sort);
    }

    public IPEDSearcher(IPEDSource ipedCase, String query, String... sort) {
        this.ipedCase = ipedCase;
        setQuery(query);
        setSorting(sort);
    }

    // TODO improve this to handle other field types
    private void setSorting(String... sort) {
        SortField[] fields = new SortField[sort.length];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = new SortField(sort[i], SortField.Type.STRING);
        }
        this.sort = new Sort(fields);
    }

    public void setTreeQuery(boolean treeQuery) {
        this.treeQuery = treeQuery;
    }

    public void setNoScoring(boolean noScore) {
        this.noScore = noScore;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public void setQuery(String queryText) {
        try {
            query = new QueryBuilder(ipedCase).getQuery(queryText);

        } catch (ParseException | QueryNodeException e) {
            throw new RuntimeException(e);
        }
    }

    public void setRewritequery(boolean rewriteQuery) {
        this.rewriteQuery = rewriteQuery;
    }

    public Query getQuery() {
        return query;
    }

    public void cancel() {
        canceled = true;
        if (collector != null)
            collector.cancel();
    }

    public SearchResult search() throws IOException {
        if (ipedCase instanceof IPEDMultiSource)
            throw new UnsupportedOperationException("Use multiSearch() method for IPEDMultiSource!"); //$NON-NLS-1$

        return luceneSearch().getSearchResult(ipedCase);
    }

    public MultiSearchResult multiSearch() throws IOException {
        if (!(ipedCase instanceof IPEDMultiSource))
            throw new UnsupportedOperationException("Use search() method for only one IPEDSource!"); //$NON-NLS-1$

        return MultiSearchResult.get((IPEDMultiSource) ipedCase, luceneSearch());
    }

    public LuceneSearchResult luceneSearch() throws IOException {
        return searchAll();
    }

    /**
     * Prepares the query applying the same transformations as searchAll():
     * query rewriting and tree-node filtering.
     * <p>
     * MatchAllDocsQuery is kept as-is instead of converting to
     * IntPoint.newRangeQuery(id, MIN, MAX) because the latter forces a full
     * BKD-tree traversal across every segment before any docs can be
     * collected — extremely slow for paginated queries that only need a
     * handful of results. getNonTreeQuery still filters tree nodes.
     */
    private Query prepareQuery() {
        Query q = this.query;
        if (!(q instanceof MatchAllDocsQuery) && rewriteQuery) {
            q = new QueryBuilder(ipedCase, true).rewriteQuery(q);
        }
        if (!treeQuery) {
            q = getNonTreeQuery(q);
        }
        return q;
    }

    /**
     * Returns the total number of matching documents without materializing results.
     * Uses Lucene's optimized count() which avoids collecting all documents.
     */
    public int count() throws IOException {
        return ipedCase.getSearcher().count(prepareQuery());
    }

    /**
     * Paginated search for a single IPEDSource. Returns only the requested page
     * of IPED item IDs plus the total hit count. Much faster than search() when
     * only a small window of results is needed, as it avoids materializing all
     * matching documents.
     *
     * @param start zero-based offset of the first result to return
     * @param rows  maximum number of results to return
     * @param totalHits single-element array; totalHits[0] is set to the total count
     * @return SearchResult containing only the requested page
     */
    public SearchResult searchPaged(int start, int rows, int[] totalHits) throws IOException {
        return searchPaged(start, rows, totalHits, null);
    }

    /**
     * Paginated search for a single IPEDSource with optional sorting.
     *
     * @param start zero-based offset of the first result to return
     * @param rows  maximum number of results to return
     * @param totalHits single-element array; totalHits[0] is set to the total count
     * @param sortOverride optional Sort to apply; if null, uses doc-id order
     * @return SearchResult containing only the requested page
     */
    public SearchResult searchPaged(int start, int rows, int[] totalHits, Sort sortOverride) throws IOException {
        return searchPaged(start, rows, totalHits, sortOverride, -1);
    }

    /**
     * Paginated search for a single IPEDSource with optional sorting.
     * When precomputedTotal >= 0, skips the expensive count() call.
     */
    public SearchResult searchPaged(int start, int rows, int[] totalHits, Sort sortOverride, int precomputedTotal)
            throws IOException {
        if (ipedCase instanceof IPEDMultiSource)
            throw new UnsupportedOperationException("Use multiSearchPaged() for IPEDMultiSource!");

        Query q = prepareQuery();
        int total = precomputedTotal >= 0 ? precomputedTotal : ipedCase.getSearcher().count(q);
        totalHits[0] = total;

        if (total == 0 || start >= total || rows <= 0) {
            return new SearchResult(new int[0], new float[0]);
        }

        int numToCollect = Math.min(start + rows, total);
        Sort sort = sortOverride != null ? sortOverride : new Sort(SortField.FIELD_DOC);
        TopFieldDocs topDocs = ipedCase.getSearcher().search(q, numToCollect, sort);
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;

        int pageStart = Math.min(start, scoreDocs.length);
        int pageEnd = Math.min(start + rows, scoreDocs.length);
        int pageSize = pageEnd - pageStart;

        int[] ids = new int[pageSize];
        float[] scores = new float[pageSize];
        for (int i = 0; i < pageSize; i++) {
            ids[i] = ipedCase.getId(scoreDocs[pageStart + i].doc);
            scores[i] = scoreDocs[pageStart + i].score;
        }
        return new SearchResult(ids, scores);
    }

    /**
     * Paginated single-source search using searchAfter for efficient deep paging.
     * Instead of collecting start+rows docs, it only collects rows docs after the cursor.
     *
     * @param rows      maximum number of results to return
     * @param totalHits single-element array; totalHits[0] is set to the total count
     * @param after     the last ScoreDoc from the previous page (null for first page)
     * @param sortOverride optional Sort; if null, uses doc-id order
     * @return SearchAfterResult with the page items and the last ScoreDoc for the next call
     */
    public SearchAfterResult searchAfterPaged(int rows, int[] totalHits, ScoreDoc after, Sort sortOverride)
            throws IOException {
        if (ipedCase instanceof IPEDMultiSource)
            throw new UnsupportedOperationException("Use multiSearchAfterPaged() for IPEDMultiSource!");

        Query q = prepareQuery();
        int total = ipedCase.getSearcher().count(q);
        totalHits[0] = total;

        if (total == 0 || rows <= 0) {
            return new SearchAfterResult(new int[0], new float[0], null);
        }

        Sort sort = sortOverride != null ? sortOverride : new Sort(SortField.FIELD_DOC);
        TopFieldDocs topDocs = (after != null)
                ? ipedCase.getSearcher().searchAfter(after, q, rows, sort, true)
                : ipedCase.getSearcher().search(q, rows, sort, true);
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;

        int[] ids = new int[scoreDocs.length];
        float[] scores = new float[scoreDocs.length];
        for (int i = 0; i < scoreDocs.length; i++) {
            ids[i] = ipedCase.getId(scoreDocs[i].doc);
            scores[i] = scoreDocs[i].score;
        }

        ScoreDoc lastDoc = scoreDocs.length > 0 ? scoreDocs[scoreDocs.length - 1] : null;
        return new SearchAfterResult(ids, scores, lastDoc);
    }

    /**
     * Paginated multi-source search using searchAfter for efficient deep paging.
     *
     * @param rows      maximum number of results to return
     * @param totalHits single-element array; totalHits[0] is set to the total count
     * @param after     the last ScoreDoc from the previous page (null for first page)
     * @param sortOverride optional Sort; if null, uses doc-id order
     * @return SearchAfterMultiResult with the page items and the last ScoreDoc
     */
    public SearchAfterMultiResult multiSearchAfterPaged(int rows, int[] totalHits, ScoreDoc after,
            Sort sortOverride) throws IOException {
        if (!(ipedCase instanceof IPEDMultiSource))
            throw new UnsupportedOperationException("Use searchAfterPaged() for single IPEDSource!");

        IPEDMultiSource multiSource = (IPEDMultiSource) ipedCase;
        Query q = prepareQuery();
        int total = multiSource.getSearcher().count(q);
        totalHits[0] = total;

        if (total == 0 || rows <= 0) {
            return new SearchAfterMultiResult(new IItemId[0], null);
        }

        Sort sort = sortOverride != null ? sortOverride : new Sort(SortField.FIELD_DOC);
        TopFieldDocs topDocs = (after != null)
                ? multiSource.getSearcher().searchAfter(after, q, rows, sort, true)
                : multiSource.getSearcher().search(q, rows, sort, true);
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;

        IItemId[] result = new IItemId[scoreDocs.length];
        for (int i = 0; i < scoreDocs.length; i++) {
            result[i] = multiSource.getItemId(scoreDocs[i].doc);
        }

        ScoreDoc lastDoc = scoreDocs.length > 0 ? scoreDocs[scoreDocs.length - 1] : null;
        return new SearchAfterMultiResult(result, lastDoc);
    }

    /** Result of searchAfter on a single source. */
    public static class SearchAfterResult {
        private final int[] ids;
        private final float[] scores;
        private final ScoreDoc lastScoreDoc;

        public SearchAfterResult(int[] ids, float[] scores, ScoreDoc lastScoreDoc) {
            this.ids = ids;
            this.scores = scores;
            this.lastScoreDoc = lastScoreDoc;
        }

        public int[] getIds() { return ids; }
        public float[] getScores() { return scores; }
        public ScoreDoc getLastScoreDoc() { return lastScoreDoc; }
    }

    /** Result of searchAfter on a multi-source. */
    public static class SearchAfterMultiResult {
        private final IItemId[] items;
        private final ScoreDoc lastScoreDoc;

        public SearchAfterMultiResult(IItemId[] items, ScoreDoc lastScoreDoc) {
            this.items = items;
            this.lastScoreDoc = lastScoreDoc;
        }

        public IItemId[] getItems() { return items; }
        public ScoreDoc getLastScoreDoc() { return lastScoreDoc; }
    }

    /**
     * Paginated search for an IPEDMultiSource. Returns only the requested page
     * of IItemId results plus the total hit count.
     *
     * @param start zero-based offset of the first result to return
     * @param rows  maximum number of results to return
     * @param totalHits single-element array; totalHits[0] is set to the total count
     * @return array of IItemId containing only the requested page
     */
    public IItemId[] multiSearchPaged(int start, int rows, int[] totalHits) throws IOException {
        return multiSearchPaged(start, rows, totalHits, null);
    }

    /**
     * Paginated search for an IPEDMultiSource with optional sorting.
     *
     * @param start zero-based offset of the first result to return
     * @param rows  maximum number of results to return
     * @param totalHits single-element array; totalHits[0] is set to the total count
     * @param sortOverride optional Sort to apply; if null, uses doc-id order
     * @return array of IItemId containing only the requested page
     */
    public IItemId[] multiSearchPaged(int start, int rows, int[] totalHits, Sort sortOverride) throws IOException {
        return multiSearchPaged(start, rows, totalHits, sortOverride, -1);
    }

    /**
     * Paginated search for an IPEDMultiSource with optional sorting.
     * When precomputedTotal >= 0, skips the expensive count() call.
     *
     * @param start zero-based offset of the first result to return
     * @param rows  maximum number of results to return
     * @param totalHits single-element array; totalHits[0] is set to the total count
     * @param sortOverride optional Sort to apply; if null, uses doc-id order
     * @param precomputedTotal if >= 0, used instead of running count()
     * @return array of IItemId containing only the requested page
     */
    public IItemId[] multiSearchPaged(int start, int rows, int[] totalHits, Sort sortOverride, int precomputedTotal)
            throws IOException {
        if (!(ipedCase instanceof IPEDMultiSource))
            throw new UnsupportedOperationException("Use searchPaged() for single IPEDSource!");

        IPEDMultiSource multiSource = (IPEDMultiSource) ipedCase;
        Query q = prepareQuery();
        int total = precomputedTotal >= 0 ? precomputedTotal : multiSource.getSearcher().count(q);
        totalHits[0] = total;

        if (total == 0 || start >= total || rows <= 0) {
            return new IItemId[0];
        }

        int numToCollect = Math.min(start + rows, total);
        Sort sort = sortOverride != null ? sortOverride : new Sort(SortField.FIELD_DOC);
        TopFieldDocs topDocs = multiSource.getSearcher().search(q, numToCollect, sort);
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;

        int pageStart = Math.min(start, scoreDocs.length);
        int pageEnd = Math.min(start + rows, scoreDocs.length);
        int pageSize = pageEnd - pageStart;

        IItemId[] result = new IItemId[pageSize];
        for (int i = 0; i < pageSize; i++) {
            result[i] = multiSource.getItemId(scoreDocs[pageStart + i].doc);
        }
        return result;
    }

    private LuceneSearchResult searchAll() throws IOException {

        // System.out.println("searching");

        Query query = this.query;
        // Keep MatchAllDocsQuery as-is: it has O(1) setup, whereas
        // IntPointRangeQuery(id, MIN, MAX) scans the entire BKD tree.
        if (!(query instanceof MatchAllDocsQuery)) {
            if (rewriteQuery) {
                query = new QueryBuilder(ipedCase, true).rewriteQuery(query);
            }
        }
        if (!treeQuery) {
            query = getNonTreeQuery(query);
        }

        collector = new NoScoringCollector(ipedCase.getReader().maxDoc());
        try {
            ipedCase.getSearcher().search(query, collector);

        } catch (InterruptedIOException e) {
            // e.printStackTrace();
        }
        // do not compute scores (slow) when result set is large
        if (noScore || collector.getTotalHits() > MAX_SIZE_TO_SCORE || canceled)
            return collector.getSearchResults();

        // otherwise get results computing score
        LuceneSearchResult searchResult = new LuceneSearchResult(0);

        // sort by index doc order: needed by features using docValues that iterate over results
        Sort sort = null;
        if (this.sort != null) {
            sort = this.sort;
        } else {
            sort = new Sort(SortField.FIELD_DOC);
        }
        
        int maxResults = MAX_SIZE_TO_SCORE;
        ScoreDoc[] scoreDocs = null;
        do {
            ScoreDoc lastScoreDoc = null;
            if (scoreDocs != null)
                lastScoreDoc = scoreDocs[scoreDocs.length - 1];

            scoreDocs = ipedCase.getSearcher().searchAfter(lastScoreDoc, query, maxResults, sort, true).scoreDocs;

            searchResult = searchResult.addResults(scoreDocs);

        } while (scoreDocs.length > 0 && !canceled);

        return searchResult;
    }
    
    public boolean hasDocId(int docId) {
        if (collector != null) {
            return collector.bits.get(docId);
        }
        return true;
    }

    private Query getNonTreeQuery(Query query) {
        BooleanQuery.Builder result = new BooleanQuery.Builder();
        result.add(query, Occur.MUST);
        result.add(new TermQuery(new Term(IndexItem.TREENODE, "true")), Occur.MUST_NOT); //$NON-NLS-1$
        return result.build();
    }

}
