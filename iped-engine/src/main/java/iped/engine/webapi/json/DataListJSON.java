package iped.engine.webapi.json;

import java.util.Arrays;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DataListModel puts an array in a "data" property: { "data": [] }
 */
@Schema(name = "DataList")
public class DataListJSON<T> {
    private List<T> IDs;

    public DataListJSON(T[] IDs) {
        this.IDs = Arrays.asList(IDs);
    }

    public DataListJSON(List<T> IDs) {
        this.IDs = IDs;
    }

    @Schema
    public List<T> getData() {
        return this.IDs;
    }
}