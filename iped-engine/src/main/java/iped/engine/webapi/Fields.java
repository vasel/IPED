package iped.engine.webapi;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.engine.data.IPEDSource;
import iped.engine.webapi.json.FieldInfoJSON;

/**
 * Endpoint to list all sortable fields available in the index.
 */
@Tag(name = "Fields")
@Path("fields")
public class Fields {

    @Operation(summary = "List all sortable index fields",
               description = "Returns the list of field names that can be used with the 'sortField' parameter in the /search endpoint, along with their sort type (STRING, LONG, FLOAT, DOUBLE).")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<FieldInfoJSON> listSortableFields() {
        IPEDSource source = Sources.multiSource;
        if (source == null) {
            return new ArrayList<>();
        }
        List<SortFieldHelper.SortableField> fields = SortFieldHelper.getSortableFields(source);
        List<FieldInfoJSON> result = new ArrayList<>(fields.size() + 1);
        // Add special "relevance" pseudo-field
        result.add(new FieldInfoJSON("relevance", "SCORE"));
        for (SortFieldHelper.SortableField sf : fields) {
            result.add(new FieldInfoJSON(sf.name, sf.type));
        }
        return result;
    }
}
