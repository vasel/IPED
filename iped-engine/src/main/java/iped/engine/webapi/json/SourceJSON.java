package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * SourceModel represents a IPED source: { "id": "A", "path": "string" }
 */
public class SourceJSON {
    private String id;
    private String path;
    private int totalItems;
    private double totalSizeMB;
    private String indexDir;

    @Schema
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Schema
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Schema
    public int getTotalItems() {
        return totalItems;
    }

    public void setTotalItems(int totalItems) {
        this.totalItems = totalItems;
    }

    @Schema
    public double getTotalSizeMB() {
        return totalSizeMB;
    }

    public void setTotalSizeMB(double totalSizeMB) {
        this.totalSizeMB = totalSizeMB;
    }

    @Schema
    public String getIndexDir() {
        return indexDir;
    }

    public void setIndexDir(String indexDir) {
        this.indexDir = indexDir;
    }
}
