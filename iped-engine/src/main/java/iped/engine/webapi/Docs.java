package iped.engine.webapi;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.engine.webapi.json.DocPropsJSON;

@Tag(name = "Documents")
@Path("sources/{sourceID}/docs")
public class Docs {

    @Operation(summary = "Get document's properties")
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public static DocPropsJSON properties(@PathParam("sourceID") String sourceID, @PathParam("id") int id)
            throws IOException {
        IIPEDSource source = Sources.getSource(sourceID);
        return buildDocProps(source, sourceID, id, null);
    }

    /**
     * Builds a DocPropsJSON with optional field filtering. When {@code fieldsFilter}
     * is null, all fields are included. Otherwise, only the specified fields are
     * fetched from the Lucene document.
     */
    public static DocPropsJSON buildDocProps(IIPEDSource source, String sourceID, int id, Set<String> fieldsFilter)
            throws IOException {
        int luceneID = source.getLuceneId(id);
        Document doc = source.getReader().document(luceneID);

        DocPropsJSON result = new DocPropsJSON();
        result.setSource(sourceID);
        result.setId(id);
        result.setLuceneId(luceneID);

        Map<String, String[]> properties = new HashMap<String, String[]>();
        if (fieldsFilter == null || fieldsFilter.isEmpty()) {
            for (IndexableField field : doc.getFields()) {
                String[] values = doc.getValues(field.name());
                properties.put(field.name(), values);
            }
        } else {
            Set<String> normalized = new HashSet<>();
            for (String f : fieldsFilter) {
                if (f != null && !f.isBlank()) {
                    normalized.add(f.trim());
                }
            }
            for (String fieldName : normalized) {
                String[] values = doc.getValues(fieldName);
                if (values != null && values.length > 0) {
                    properties.put(fieldName, values);
                }
            }
        }
        result.setProperties(properties);

        result.setBookmarks(source.getBookmarks().getBookmarkList(id));
        result.setSelected(source.getBookmarks().isChecked(id));

        return result;
    }
}
