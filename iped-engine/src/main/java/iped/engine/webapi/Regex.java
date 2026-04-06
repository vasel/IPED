package iped.engine.webapi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.engine.webapi.json.DataListJSON;
import iped.engine.webapi.json.RegexPatternJSON;

/**
 * REST API endpoint for regex pattern statistics.
 */
@Tag(name = "Regex")
@Path("regex")
public class Regex {

    private static final String REGEX_PREFIX = "Regex:";
    private static final int DEFAULT_MAX_VALUES = 1000;

    @Operation(summary = "Get aggregated regex hits statistics")
    @GET
    @Path("stats")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RegexPatternJSON> getStats(
            @Parameter(description = "Maximum number of values per pattern") 
            @QueryParam("maxValues") @DefaultValue("1000") int maxValues,
            @Parameter(description = "Source ID (optional, searches all sources if empty)")
            @QueryParam("sourceID") @DefaultValue("") String sourceID) {
        
        List<RegexPatternJSON> results = new ArrayList<>();
        Set<String> processedPatterns = new HashSet<>();
        
        if (sourceID.isEmpty()) {
            // Search across all sources
            for (IIPEDSource source : Sources.multiSource.getAtomicSources()) {
                collectRegexStats(source, results, processedPatterns, maxValues);
            }
        } else {
            // Search specific source(s)
            String[] sourceIds = sourceID.split(",");
            for (String srcId : sourceIds) {
                String trimmedSrcId = srcId.trim();
                if (trimmedSrcId.isEmpty()) continue;
                IIPEDSource source = Sources.getSource(trimmedSrcId);
                collectRegexStats(source, results, processedPatterns, maxValues);
            }
        }
        
        return results;
    }

    @Operation(summary = "Get values for a specific regex pattern")
    @GET
    @Path("stats/{pattern}")
    @Produces(MediaType.APPLICATION_JSON)
    public RegexPatternJSON getPatternStats(
            @PathParam("pattern") String pattern,
            @Parameter(description = "Maximum number of values") 
            @QueryParam("maxValues") @DefaultValue("1000") int maxValues,
            @Parameter(description = "Source ID (optional, searches all sources if empty)")
            @QueryParam("sourceID") @DefaultValue("") String sourceID) {
        
        String fieldName = REGEX_PREFIX + pattern;
        Set<String> allValues = new HashSet<>();
        
        if (sourceID.isEmpty()) {
            // Search across all sources
            for (IIPEDSource source : Sources.multiSource.getAtomicSources()) {
                collectFieldValues(source, fieldName, allValues, maxValues);
            }
        } else {
            // Search specific source(s)
            String[] sourceIds = sourceID.split(",");
            for (String srcId : sourceIds) {
                String trimmedSrcId = srcId.trim();
                if (trimmedSrcId.isEmpty()) continue;
                IIPEDSource source = Sources.getSource(trimmedSrcId);
                collectFieldValues(source, fieldName, allValues, maxValues);
            }
        }
        
        return new RegexPatternJSON(pattern, new ArrayList<>(allValues));
    }

    @Operation(summary = "List all available regex patterns")
    @GET
    @Path("patterns")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<String> getPatterns(
            @Parameter(description = "Source ID (optional, searches all sources if empty)")
            @QueryParam("sourceID") @DefaultValue("") String sourceID) {
        
        Set<String> patterns = new HashSet<>();
        
        if (sourceID.isEmpty()) {
            // Search across all sources
            for (IIPEDSource source : Sources.multiSource.getAtomicSources()) {
                collectPatternNames(source, patterns);
            }
        } else {
            // Search specific source(s)
            String[] sourceIds = sourceID.split(",");
            for (String srcId : sourceIds) {
                String trimmedSrcId = srcId.trim();
                if (trimmedSrcId.isEmpty()) continue;
                IIPEDSource source = Sources.getSource(trimmedSrcId);
                collectPatternNames(source, patterns);
            }
        }
        
        return new DataListJSON<>(new ArrayList<>(patterns));
    }

    /**
     * Collect regex statistics from a single source.
     */
    private void collectRegexStats(IIPEDSource source, List<RegexPatternJSON> results, 
            Set<String> processedPatterns, int maxValues) {
        try {
            IndexReader reader = source.getReader();
            
            for (LeafReaderContext ctx : reader.leaves()) {
                for (var fieldInfo : ctx.reader().getFieldInfos()) {
                    String fieldName = fieldInfo.name;
                    
                    if (fieldName.startsWith(REGEX_PREFIX)) {
                        String patternName = fieldName.substring(REGEX_PREFIX.length());
                        
                        // Find or create the pattern entry
                        RegexPatternJSON patternEntry = null;
                        for (RegexPatternJSON existing : results) {
                            if (existing.getPattern().equals(patternName)) {
                                patternEntry = existing;
                                break;
                            }
                        }
                        if (patternEntry == null) {
                            patternEntry = new RegexPatternJSON(patternName, new ArrayList<>());
                            results.add(patternEntry);
                        }
                        
                        // Collect values from this field
                        Terms terms = ctx.reader().terms(fieldName);
                        if (terms != null) {
                            TermsEnum termsEnum = terms.iterator();
                            while (termsEnum.next() != null && patternEntry.getValues().size() < maxValues) {
                                String value = termsEnum.term().utf8ToString();
                                if (!patternEntry.getValues().contains(value)) {
                                    patternEntry.getValues().add(value);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error reading regex stats: " + e.getMessage(), e);
        }
    }

    /**
     * Collect values for a specific field from a single source.
     */
    private void collectFieldValues(IIPEDSource source, String fieldName, Set<String> values, int maxValues) {
        try {
            IndexReader reader = source.getReader();
            
            for (LeafReaderContext ctx : reader.leaves()) {
                Terms terms = ctx.reader().terms(fieldName);
                if (terms != null) {
                    TermsEnum termsEnum = terms.iterator();
                    while (termsEnum.next() != null && values.size() < maxValues) {
                        String value = termsEnum.term().utf8ToString();
                        values.add(value);
                    }
                }
                if (values.size() >= maxValues) break;
            }
        } catch (Exception e) {
            throw new RuntimeException("Error reading field values: " + e.getMessage(), e);
        }
    }

    /**
     * Collect pattern names from a single source.
     */
    private void collectPatternNames(IIPEDSource source, Set<String> patterns) {
        try {
            IndexReader reader = source.getReader();
            
            for (LeafReaderContext ctx : reader.leaves()) {
                for (var fieldInfo : ctx.reader().getFieldInfos()) {
                    String fieldName = fieldInfo.name;
                    
                    if (fieldName.startsWith(REGEX_PREFIX)) {
                        String patternName = fieldName.substring(REGEX_PREFIX.length());
                        patterns.add(patternName);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error reading pattern names: " + e.getMessage(), e);
        }
    }
}
