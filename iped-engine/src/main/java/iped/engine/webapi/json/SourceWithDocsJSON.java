package iped.engine.webapi.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response wrapper for search results with inline document properties.
 */
@Schema(name = "SourceWithDocs")
public class SourceWithDocsJSON {
    private int total;
    private int start;
    private int rows;
    private List<DocIDGroupWithDocsJSON> data;

    public SourceWithDocsJSON() {
    }

    public SourceWithDocsJSON(Map<String, List<Integer>> idsBySource,
            Map<String, List<DocPropsJSON>> docsBySource, int total, int start, int rows) {
        this.total = total;
        this.start = start;
        this.rows = rows;
        this.data = new ArrayList<>();
        if (idsBySource != null) {
            for (Map.Entry<String, List<Integer>> entry : idsBySource.entrySet()) {
                String src = entry.getKey();
                List<Integer> ids = entry.getValue();
                List<DocPropsJSON> docs = docsBySource != null ? docsBySource.get(src) : null;
                this.data.add(new DocIDGroupWithDocsJSON(src, ids, docs));
            }
        }
    }

    @Schema(description = "Total number of results")
    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    @Schema(description = "Starting offset")
    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    @Schema(description = "Number of rows requested")
    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    @Schema(description = "List of sources with ids and inline docs")
    public List<DocIDGroupWithDocsJSON> getData() {
        return data;
    }

    public void setData(List<DocIDGroupWithDocsJSON> data) {
        this.data = data;
    }
}
