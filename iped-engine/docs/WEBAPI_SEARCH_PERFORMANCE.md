# WebAPI Search Performance Optimizations — Frontend Adaptation Guide

This document describes four new backend optimizations to the `/search` endpoint and how the frontend should be adapted to take advantage of them.

---

## 1. Cursor-Based Pagination (`searchAfter`)

### What changed
The `/search` endpoint now supports **cursor-based pagination** via an opaque `cursor` token, eliminating the performance penalty of deep `start` offsets.

### New parameters / fields

| Direction | Name | Type | Description |
|-----------|------|------|-------------|
| **Request** | `cursor` | `string` (query param) | Opaque token from a previous response's `nextCursor`. Omit or pass empty for the first page. When provided, `start` is ignored. |
| **Response** | `nextCursor` | `string \| null` | Token to pass as `cursor` in the next request. `null` means there are no more pages. |

### Frontend adaptation

**Before (offset-based):**
```js
// Page 1
GET /search?q=*&rows=100&start=0

// Page 2
GET /search?q=*&rows=100&start=100

// Page N (slow for large N)
GET /search?q=*&rows=100&start=10000
```

**After (cursor-based):**
```js
// Page 1 — no cursor
const page1 = await fetch('/search?q=*&rows=100');
const data1 = await page1.json();
// data1.nextCursor = "UwAAAbcAAA..."

// Page 2 — pass cursor, start is ignored
const page2 = await fetch(`/search?q=*&rows=100&cursor=${encodeURIComponent(data1.nextCursor)}`);
const data2 = await page2.json();

// Last page
// data.nextCursor === null  →  no more pages
```

**Key rules:**
- On the **first** request, omit `cursor` (or pass empty). The response will include `nextCursor` if there are more results.
- On **subsequent** requests, pass the `nextCursor` value from the previous response as `cursor`. The `start` parameter is ignored when `cursor` is present.
- `nextCursor` is `null` when the result set is exhausted.
- Cursors are **opaque** — do not parse, modify, or persist them across different queries/sorts.
- If the user changes the query (`q`), sort, or source selection, **discard** the cursor and start fresh.

**Backward compatibility:** Offset-based pagination (`start` + `rows`) still works exactly as before. Cursor-based pagination is opt-in.

---

## 2. LRU Search Result Cache

### What changed
The backend now caches recent search results (keyed by `q`, `sourceID`, `sortField`, `sortOrder`, `start`, `rows`). Repeated identical requests are served from memory without hitting Lucene.

### Configuration (server-side)
| System Property | Default | Description |
|----------------|---------|-------------|
| `iped.webapi.searchcache.enabled` | `true` | Enable/disable the cache |
| `iped.webapi.searchcache.maxsize` | `256` | Max cached entries |
| `iped.webapi.searchcache.ttl` | `60` | Seconds before eviction |

### Frontend adaptation
- **No API changes required.** Identical repeat requests are transparently faster.
- The cache is automatically **invalidated** when bookmarks are modified (add/remove/create/delete/rename).
- If you implement a "refresh" button, simply re-send the same request — the TTL ensures stale data expires within 60s by default.
- Cursor-based requests (`cursor` parameter present) **bypass** the cache, since they represent stateful progression through results.

---

## 3. Parallel Multi-Source Search

### What changed
When searching multiple explicit sources (`sourceID=src1,src2,src3`) **without sorting**, the backend now counts results per source **in parallel** using a thread pool, significantly reducing response time.

### Configuration (server-side)
| System Property | Default | Description |
|----------------|---------|-------------|
| `iped.webapi.search.threads` | `4` | Number of threads for parallel search |

### Frontend adaptation
- **No API changes required.** The speedup is automatic.
- For best performance when searching multiple sources, **omit `sortField`** when global sort order is not needed. This allows the parallel execution path.
- When `sortField` is specified with multiple sources, the backend falls back to a global sorted search (still correct, but uses single-threaded path).

---

## 4. Gzip Response Compression

### What changed
The server now supports HTTP gzip content encoding. Clients that send `Accept-Encoding: gzip` will receive compressed responses, reducing network transfer size by 60-80% for JSON payloads.

### Frontend adaptation

**For `fetch` API:**
Modern browsers automatically send `Accept-Encoding: gzip` and decompress responses. **No code changes needed** for browser-based frontends.

**For non-browser clients (Node.js, curl, etc.):**
```js
// Node.js with node-fetch
const response = await fetch('http://server:8080/search?q=*', {
  headers: { 'Accept-Encoding': 'gzip' }
});
// Response is automatically decompressed by node-fetch
```

```bash
# curl
curl -H "Accept-Encoding: gzip" --compressed http://server:8080/search?q=*
```

---

## 5. Precomputed Stats (from previous iteration)

### Reminder
The following endpoints return precomputed counts at no extra cost:

| Endpoint | New fields |
|----------|-----------|
| `GET /sources` | Each source now includes `categoryCounts` and `bookmarkCounts` maps |
| `GET /categories` | Each category includes `count` (total) |
| `GET /bookmarks` | Each bookmark includes `count` (total) and `perSource` map |

Use these to display counts in the UI sidebar without additional search requests.

---

## Migration Checklist

- [ ] **Cursor pagination**: Update "load more" / infinite scroll / pagination logic to store `nextCursor` and pass it on subsequent requests. Reset cursor when query/sort/source changes.
- [ ] **Gzip**: Verify your HTTP client sends `Accept-Encoding: gzip` (browsers do this automatically).
- [ ] **Stats**: Use precomputed counts from `/sources`, `/categories`, `/bookmarks` for sidebar displays instead of issuing `q=*` count queries.
- [ ] **No changes needed** for LRU cache or parallel multi-source — these are transparent backend optimizations.

---

## Complete Example: Paginated Search with Cursor

```js
async function searchAll(query, sourceID, rows = 100) {
  const results = [];
  let cursor = null;
  let total = 0;

  do {
    const params = new URLSearchParams({ q: query, rows: String(rows) });
    if (sourceID) params.set('sourceID', sourceID);
    if (cursor) params.set('cursor', cursor);

    const resp = await fetch(`/search?${params}`);
    const data = await resp.json();

    results.push(...data.data);
    total = data.total;
    cursor = data.nextCursor;

  } while (cursor !== null);

  console.log(`Fetched ${results.length} of ${total} total results`);
  return results;
}
```
