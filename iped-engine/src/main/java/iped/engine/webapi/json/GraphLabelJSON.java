package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JSON representation of a graph label with its count.
 */
@Schema(name = "GraphLabel")
public class GraphLabelJSON {
    private String name;
    private long count;

    public GraphLabelJSON() {
    }

    public GraphLabelJSON(String name, long count) {
        this.name = name;
        this.count = count;
    }

    @Schema(description = "Label name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema(description = "Number of nodes with this label")
    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
