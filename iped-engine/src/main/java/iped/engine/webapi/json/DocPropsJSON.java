package iped.engine.webapi.json;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DocPropsModel list properties of a document: { "source": "A", "id": 0,
 * "luceneId": 0, "properties": {}, "bookmarks": [ "string" ], "selected": false
 * }
 */
public class DocPropsJSON {
    private String source;
    private int id;
    private int luceneId;
    private Map<String, String[]> properties;
    private List<String> bookmarks;
    private boolean selected;

    @Schema
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Schema
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Schema
    public int getLuceneId() {
        return luceneId;
    }

    public void setLuceneId(int luceneId) {
        this.luceneId = luceneId;
    }

    @Schema
    public Map<String, String[]> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String[]> properties) {
        this.properties = properties;
    }

    @Schema
    public List<String> getBookmarks() {
        return bookmarks;
    }

    public void setBookmarks(List<String> bookmarks) {
        this.bookmarks = bookmarks;
    }

    @Schema
    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}
