package iped.engine.webapi.json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * SourceToIDsModel lists documents grouped by source: { "data": [ { "source":
 * "A", "ids": [ 1, 2, 3 ] }, { "source": 1, "ids": [ 1, 2, 3 ] } ] }
 */
public class SourceToIDsJSON {

    private Map<String, List<Integer>> sourceToids;
    private int total;
    private int start;
    private int rows;

    public SourceToIDsJSON() {
        this.sourceToids = new HashMap<String, List<Integer>>();
    }

    public SourceToIDsJSON(List<DocIDJSON> docs) {
        this(docs, docs.size(), 0, docs.size());
    }

    public SourceToIDsJSON(List<DocIDJSON> docs, int total, int start, int rows) {
        this();
        this.total = total;
        this.start = start;
        this.rows = rows;
        for (DocIDJSON doc : docs) {
            String source = doc.getSource();
            Integer id = doc.getId();
            if (!this.sourceToids.containsKey(source)) {
                this.sourceToids.put(source, new ArrayList<Integer>());
            }
            List<Integer> ids = this.sourceToids.get(source);
            ids.add(id);
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

    @Schema(description = "List of document IDs grouped by source")
    public List<DocIDGroupJSON> getData() {
        List<DocIDGroupJSON> result = new ArrayList<DocIDGroupJSON>();
        for (Map.Entry<String, List<Integer>> entry : this.sourceToids.entrySet()) {
            result.add(new DocIDGroupJSON(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public void setData(List<DocIDGroupJSON> data) {
        for (DocIDGroupJSON grp : data) {
            this.sourceToids.put(grp.getSource(), grp.getIds());
        }
    }
}
