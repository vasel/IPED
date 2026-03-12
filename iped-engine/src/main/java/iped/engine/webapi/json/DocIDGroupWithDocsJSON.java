package iped.engine.webapi.json;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Group of document IDs with optional inline document data.
 */
@Schema(name = "DocIDGroupWithDocs")
public class DocIDGroupWithDocsJSON {
    private String source;
    private List<Integer> ids;
    private List<DocPropsJSON> docs;

    public DocIDGroupWithDocsJSON() {
    }

    public DocIDGroupWithDocsJSON(String source, List<Integer> ids, List<DocPropsJSON> docs) {
        this.source = source;
        this.ids = ids;
        this.docs = docs;
    }

    @Schema
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Schema
    public List<Integer> getIds() {
        return ids;
    }

    public void setIds(List<Integer> ids) {
        this.ids = ids;
    }

    @Schema(description = "Inline document data when requested via 'fl'")
    public List<DocPropsJSON> getDocs() {
        return docs;
    }

    public void setDocs(List<DocPropsJSON> docs) {
        this.docs = docs;
    }
}
