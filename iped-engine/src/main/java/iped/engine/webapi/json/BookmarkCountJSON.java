package iped.engine.webapi.json;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

public class BookmarkCountJSON {
    private String name;
    private int count;
    private Map<String, Integer> perSource;

    public BookmarkCountJSON() {
    }

    public BookmarkCountJSON(String name, int count, Map<String, Integer> perSource) {
        this.name = name;
        this.count = count;
        this.perSource = perSource;
    }

    @Schema
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema
    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    @Schema
    public Map<String, Integer> getPerSource() {
        return perSource;
    }

    public void setPerSource(Map<String, Integer> perSource) {
        this.perSource = perSource;
    }
}
