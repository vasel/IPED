package iped.engine.webapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.engine.webapi.SearchStats;
import iped.engine.webapi.json.CategoryCountJSON;
import iped.engine.webapi.json.DataListJSON;

@Tag(name = "Categories")
@Path("categories")
public class Categories {

    @Operation(summary = "List categories")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<CategoryCountJSON> get() throws Exception {
        SearchStats stats = Sources.getSearchStats();
        List<CategoryCountJSON> data = new ArrayList<>();

        if (stats != null && stats.getCategoryTotals() != null) {
            for (Map.Entry<String, Integer> e : stats.getCategoryTotals().entrySet()) {
                data.add(new CategoryCountJSON(e.getKey(), e.getValue()));
            }
        } else {
            List<String> categories = Sources.multiSource.getLeafCategories();
            for (String c : categories) {
                data.add(new CategoryCountJSON(c, 0));
            }
        }

        return new DataListJSON<CategoryCountJSON>(data);
    }
}