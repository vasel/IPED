package iped.engine.webapi.json;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mobile device information extracted from UFED Device Info items.
 */
@Schema(name = "MobileInfo")
public class MobileInfoJSON {

    private int deviceInfoItemId;
    private String deviceInfoContentUrl;
    private List<DeviceInfoEntryJSON> deviceProperties;

    @Schema(description = "IPED item ID of the UFED Device Info item")
    public int getDeviceInfoItemId() {
        return deviceInfoItemId;
    }

    public void setDeviceInfoItemId(int deviceInfoItemId) {
        this.deviceInfoItemId = deviceInfoItemId;
    }

    @Schema(description = "URL to fetch the Device Info HTML content")
    public String getDeviceInfoContentUrl() {
        return deviceInfoContentUrl;
    }

    public void setDeviceInfoContentUrl(String deviceInfoContentUrl) {
        this.deviceInfoContentUrl = deviceInfoContentUrl;
    }

    @Schema(description = "Structured device properties extracted from the Device Info HTML")
    public List<DeviceInfoEntryJSON> getDeviceProperties() {
        return deviceProperties;
    }

    public void setDeviceProperties(List<DeviceInfoEntryJSON> deviceProperties) {
        this.deviceProperties = deviceProperties;
    }
}
