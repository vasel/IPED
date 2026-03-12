package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

public class CategoryCountJSON {
    private String name;
    private int count;

    public CategoryCountJSON() {
    }

    public CategoryCountJSON(String name, int count) {
        this.name = name;
        this.count = count;
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
}
