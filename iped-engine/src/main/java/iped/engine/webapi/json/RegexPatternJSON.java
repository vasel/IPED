package iped.engine.webapi.json;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * JSON representation of regex pattern statistics.
 */
@Schema(name = "RegexPattern")
public class RegexPatternJSON {
    
    private String pattern;
    private List<String> values;

    public RegexPatternJSON() {
    }

    public RegexPatternJSON(String pattern, List<String> values) {
        this.pattern = pattern;
        this.values = values;
    }

    @Schema(description = "Pattern name (without 'Regex:' prefix)")
    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    @Schema(description = "List of unique values found for this pattern")
    public List<String> getValues() {
        return values;
    }

    public void setValues(List<String> values) {
        this.values = values;
    }
}
