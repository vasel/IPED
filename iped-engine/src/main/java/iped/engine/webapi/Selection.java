package iped.engine.webapi;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IItemId;
import iped.data.IMultiBookmarks;
import iped.engine.data.ItemId;
import iped.engine.search.IPEDSearcher;
import iped.engine.webapi.json.DocIDJSON;
import iped.engine.webapi.json.SourceToIDsJSON;
import iped.search.IIPEDSearcher;
import iped.search.IMultiSearchResult;

@Tag(name = "Selection")
@Path("selection")
public class Selection {

    @Operation(summary = "List selected documents")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SourceToIDsJSON get() throws Exception {

        IIPEDSearcher searcher = new IPEDSearcher(Sources.multiSource, "");
        IMultiSearchResult result = searcher.multiSearch();
        result = Sources.multiSource.getMultiBookmarks().filterChecked(result);

        List<DocIDJSON> docs = new ArrayList<DocIDJSON>();
        for (IItemId id : result.getIterator()) {
            docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
        }

        return new SourceToIDsJSON(docs);
    }

    @Operation(summary = "Add documents to selection")
    @PUT
    @Path("add")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response add(@Parameter(required = true) DocIDJSON[] docs) {
        IMultiBookmarks mm = Sources.multiSource.getMultiBookmarks();
        for (DocIDJSON d : docs) {
            mm.setChecked(true, new ItemId(Sources.sourceStringToInt.get(d.getSource()), d.getId()));
        }
        mm.saveState();
        return Response.ok().build();
    }

    @Operation(summary = "Remove documents from selection")
    @PUT
    @Path("remove")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response remove(@Parameter(required = true) DocIDJSON[] docs) {
        IMultiBookmarks mm = Sources.multiSource.getMultiBookmarks();
        for (DocIDJSON d : docs) {
            mm.setChecked(false, new ItemId(Sources.sourceStringToInt.get(d.getSource()), d.getId()));
        }
        mm.saveState();
        return Response.ok().build();
    }

}
