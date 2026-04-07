package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A reference to an indexed report item, with a URL to fetch its content.
 */
@Schema(name = "ReportRef")
public class ReportRefJSON {

    private int id;
    private String name;
    private String contentUrl;

    public ReportRefJSON() {
    }

    public ReportRefJSON(int id, String name, String contentUrl) {
        this.id = id;
        this.name = name;
        this.contentUrl = contentUrl;
    }

    @Schema(description = "IPED item ID")
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Schema(description = "Report/item name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema(description = "URL to fetch the report content")
    public String getContentUrl() {
        return contentUrl;
    }

    public void setContentUrl(String contentUrl) {
        this.contentUrl = contentUrl;
    }
}
