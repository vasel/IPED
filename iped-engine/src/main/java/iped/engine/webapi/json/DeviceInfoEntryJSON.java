package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A single name/value property from UFED Device Info.
 */
@Schema(name = "DeviceInfoEntry")
public class DeviceInfoEntryJSON {

    private String name;
    private String value;
    private String extraction;

    public DeviceInfoEntryJSON() {
    }

    public DeviceInfoEntryJSON(String name, String value, String extraction) {
        this.name = name;
        this.value = value;
        this.extraction = extraction;
    }

    @Schema(description = "Property name (e.g. IMEI, Model, Manufacturer)")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema(description = "Property value")
    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Schema(description = "Extraction source name")
    public String getExtraction() {
        return extraction;
    }

    public void setExtraction(String extraction) {
        this.extraction = extraction;
    }
}
