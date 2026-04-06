package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Represents an index field that can be used for sorting.
 */
@Schema(description = "A sortable index field")
public class FieldInfoJSON {

    private String name;
    private String type;

    public FieldInfoJSON() {
    }

    public FieldInfoJSON(String name, String type) {
        this.name = name;
        this.type = type;
    }

    @Schema(description = "Field name as stored in the index", example = "name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema(description = "Sort type: STRING, LONG, FLOAT, DOUBLE", example = "STRING")
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
