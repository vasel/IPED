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
        List<IIPEDSource> sourcesToSearch = new ArrayList<>();
        
        if (sourceID.isEmpty()) {
            sourcesToSearch.addAll(Sources.multiSource.getAtomicSources());
        } else {
            String[] sourceIds = sourceID.split(",");
            for (String srcId : sourceIds) {
                String trimmedSrcId = srcId.trim();
                if (!trimmedSrcId.isEmpty()) {
                    IIPEDSource source = Sources.getSource(trimmedSrcId);
                    if (source != null) sourcesToSearch.add(source);
                }
            }
        }
        
        java.util.concurrent.ConcurrentHashMap<String, Set<String>> patternValuesMap = new java.util.concurrent.ConcurrentHashMap<>();

        sourcesToSearch.parallelStream().forEach(source -> {
            try {
                IndexReader reader = source.getReader();
                for (LeafReaderContext ctx : reader.leaves()) {
                    for (var fieldInfo : ctx.reader().getFieldInfos()) {
                        String fieldName = fieldInfo.name;
                        if (fieldName.startsWith(REGEX_PREFIX)) {
                            String patternName = fieldName.substring(REGEX_PREFIX.length());
                            
                            Set<String> valuesSet = patternValuesMap.computeIfAbsent(patternName, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
                            if (valuesSet.size() >= maxValues) continue;
                            
                            Terms terms = ctx.reader().terms(fieldName);
                            if (terms != null) {
                                TermsEnum termsEnum = terms.iterator();
                                while (termsEnum.next() != null && valuesSet.size() < maxValues) {
                                    valuesSet.add(termsEnum.term().utf8ToString());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Log and continue
            }
        });

        for (java.util.Map.Entry<String, Set<String>> entry : patternValuesMap.entrySet()) {
            List<String> limitedValues = new ArrayList<>(entry.getValue());
            if (limitedValues.size() > maxValues) {
                limitedValues = limitedValues.subList(0, maxValues);
            }
            results.add(new RegexPatternJSON(entry.getKey(), limitedValues));
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
        Set<String> allValues = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<IIPEDSource> sourcesToSearch = new ArrayList<>();
        
        if (sourceID.isEmpty()) {
            sourcesToSearch.addAll(Sources.multiSource.getAtomicSources());
        } else {
            String[] sourceIds = sourceID.split(",");
            for (String srcId : sourceIds) {
                String trimmedSrcId = srcId.trim();
                if (!trimmedSrcId.isEmpty()) {
                    IIPEDSource source = Sources.getSource(trimmedSrcId);
                    if (source != null) sourcesToSearch.add(source);
                }
            }
        }
        
        sourcesToSearch.parallelStream().forEach(source -> {
            try {
                IndexReader reader = source.getReader();
                for (LeafReaderContext ctx : reader.leaves()) {
                    if (allValues.size() >= maxValues) break;
                    Terms terms = ctx.reader().terms(fieldName);
                    if (terms != null) {
                        TermsEnum termsEnum = terms.iterator();
                        while (termsEnum.next() != null && allValues.size() < maxValues) {
                            allValues.add(termsEnum.term().utf8ToString());
                        }
                    }
                }
            } catch (Exception e) {
            }
        });
        
        List<String> limited = new ArrayList<>(allValues);
        if (limited.size() > maxValues) {
            limited = limited.subList(0, maxValues);
        }
        
        return new RegexPatternJSON(pattern, limited);
    }

    @Operation(summary = "List all available regex patterns")
    @GET
    @Path("patterns")
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<String> getPatterns(
            @Parameter(description = "Source ID (optional, searches all sources if empty)")
            @QueryParam("sourceID") @DefaultValue("") String sourceID) {
        
        Set<String> patterns = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<IIPEDSource> sourcesToSearch = new ArrayList<>();
        
        if (sourceID.isEmpty()) {
            sourcesToSearch.addAll(Sources.multiSource.getAtomicSources());
        } else {
            String[] sourceIds = sourceID.split(",");
            for (String srcId : sourceIds) {
                String trimmedSrcId = srcId.trim();
                if (!trimmedSrcId.isEmpty()) {
                    IIPEDSource source = Sources.getSource(trimmedSrcId);
                    if (source != null) sourcesToSearch.add(source);
                }
            }
        }
        
        sourcesToSearch.parallelStream().forEach(source -> {
            try {
                IndexReader reader = source.getReader();
                for (LeafReaderContext ctx : reader.leaves()) {
                    for (var fieldInfo : ctx.reader().getFieldInfos()) {
                        String fieldName = fieldInfo.name;
                        if (fieldName.startsWith(REGEX_PREFIX)) {
                            patterns.add(fieldName.substring(REGEX_PREFIX.length()));
                        }
                    }
                }
            } catch (Exception e) {
            }
        });
        
        return new DataListJSON<>(new ArrayList<>(patterns));
    }

}
