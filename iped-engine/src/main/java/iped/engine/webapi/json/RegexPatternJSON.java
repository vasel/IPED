package iped.engine.webapi.json;

import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * JSON representation of regex pattern statistics.
 */
@ApiModel(value = "RegexPattern")
public class RegexPatternJSON {
    
    private String pattern;
    private List<String> values;

    public RegexPatternJSON() {
    }

    public RegexPatternJSON(String pattern, List<String> values) {
        this.pattern = pattern;
        this.values = values;
    }

    @ApiModelProperty(value = "Pattern name (without 'Regex:' prefix)")
    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    @ApiModelProperty(value = "List of unique values found for this pattern")
    public List<String> getValues() {
        return values;
    }

    public void setValues(List<String> values) {
        this.values = values;
    }
}
