package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DocIDModel identifies a single document: { "source": "A", "id": 0 }
 */
@Schema(name = "Document")
public class DocIDJSON {
    private String source;
    private int id;

    public DocIDJSON() {
    }

    public DocIDJSON(String source, int id) {
        this.source = source;
        this.id = id;
    }

    @Schema
    public String getSource() {
        return this.source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Schema
    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }
}
