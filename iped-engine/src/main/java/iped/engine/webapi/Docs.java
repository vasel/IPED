package iped.engine.webapi;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.StoredFieldVisitor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.engine.webapi.json.DocPropsJSON;

@Tag(name = "Documents")
@Path("sources/{sourceID}/docs")
public class Docs {

    /**
     * Stored fields that are large binary/text payloads and must NOT be loaded
     * by default in props-batch responses. They are served by dedicated endpoints
     * (e.g. /thumb, /text/content), so loading them here is pure waste and the
     * main cause of slow batch responses.
     */
    private static final Set<String> LARGE_DEFAULT_SKIP_FIELDS = new HashSet<>(Arrays.asList(
            "content",       // BasicProps.CONTENT
            "thumbnail",     // BasicProps.THUMB — binary thumbnail bytes
            "imageFeatures"  // ImageSimilarityTask.IMAGE_FEATURES — binary
    ));

    @Operation(summary = "Get document's properties")
    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public static DocPropsJSON properties(@PathParam("sourceID") String sourceID, @PathParam("id") int id)
            throws IOException {
        IIPEDSource source = Sources.getSource(sourceID);
        if (source == null) {
            throw new javax.ws.rs.WebApplicationException("Source not found", javax.ws.rs.core.Response.Status.NOT_FOUND);
        }
        return buildDocProps(source, sourceID, id, null);
    }

    /**
     * Builds a DocPropsJSON with optional field filtering. When {@code fieldsFilter}
     * is null, all fields except known large binary fields are included. When
     * specified, only the requested fields are fetched from the Lucene document.
     * <p>
     * Properties from stored fields are cached in {@link DocPropsCache} (immutable
     * per Lucene doc); bookmarks and selection are always re-read from the live
     * source so they reflect runtime changes.
     */
    public static DocPropsJSON buildDocProps(IIPEDSource source, String sourceID, int id, Set<String> fieldsFilter)
            throws IOException {

        DocPropsJSON result = new DocPropsJSON();
        result.setSource(sourceID);
        result.setId(id);

        int luceneID = source.getLuceneId(id);
        if (luceneID < 0) {
            throw new javax.ws.rs.WebApplicationException("Document not found for id: " + id, javax.ws.rs.core.Response.Status.NOT_FOUND);
        }

        // Fast path: cached properties (only for the default "all fields" case).
        if (fieldsFilter == null || fieldsFilter.isEmpty()) {
            DocPropsCache.Entry cached = DocPropsCache.get(sourceID, id);
            if (cached != null) {
                result.setLuceneId(cached.luceneId);
                result.setProperties(cached.properties);
                result.setBookmarks(source.getBookmarks().getBookmarkList(id));
                result.setSelected(source.getBookmarks().isChecked(id));
                return result;
            }
        }

        result.setLuceneId(luceneID);

        Document doc;
        Set<String> normalizedFilter = null;
        if (fieldsFilter == null || fieldsFilter.isEmpty()) {
            // Skip large binary fields (thumbnail, imageFeatures, content) — they
            // are served by dedicated endpoints and dominate stored-fields I/O.
            DocumentStoredFieldVisitor visitor = new DocumentStoredFieldVisitor() {
                @Override
                public StoredFieldVisitor.Status needsField(FieldInfo fieldInfo) throws IOException {
                    if (LARGE_DEFAULT_SKIP_FIELDS.contains(fieldInfo.name)) {
                        return StoredFieldVisitor.Status.NO;
                    }
                    return super.needsField(fieldInfo);
                }
            };
            source.getReader().document(luceneID, visitor);
            doc = visitor.getDocument();
        } else {
            normalizedFilter = new HashSet<>();
            for (String f : fieldsFilter) {
                if (f != null && !f.isBlank()) {
                    normalizedFilter.add(f.trim());
                }
            }
            doc = source.getReader().document(luceneID, normalizedFilter);
        }

        Map<String, String[]> properties = new HashMap<>();
        if (normalizedFilter == null) {
            // Deduplicate field names before calling doc.getValues() (which does
            // a linear scan over all fields). Without dedup this loop is O(N^2)
            // since multi-valued fields appear once per value in doc.getFields().
            Set<String> seen = new LinkedHashSet<>();
            for (IndexableField field : doc.getFields()) {
                seen.add(field.name());
            }
            for (String name : seen) {
                String[] values = doc.getValues(name);
                if (values != null && values.length > 0) {
                    properties.put(name, values);
                }
            }
            // Cache the immutable property map for subsequent requests.
            DocPropsCache.put(sourceID, id, new DocPropsCache.Entry(luceneID, properties));
        } else {
            for (String fieldName : normalizedFilter) {
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
