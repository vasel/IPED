package iped.engine.webapi;

import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.engine.webapi.json.DataListJSON;

@Tag(name = "Categories")
@Path("categories")
public class Categories {

    @Operation(summary = "List categories")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<String> get() throws Exception {

        List<String> categories = Sources.multiSource.getLeafCategories();
        DataListJSON<String> result = new DataListJSON<String>(categories);

        return result;
    }
}