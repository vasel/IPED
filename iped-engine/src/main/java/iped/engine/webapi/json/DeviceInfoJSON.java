package iped.engine.webapi.json;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Top-level response for the /sources/{sourceID}/deviceinfo endpoint.
 * Contains device/system information for each forensic image (evidenceUUID)
 * within the source.
 */
@Schema(name = "DeviceInfo")
public class DeviceInfoJSON {

    private String sourceID;
    private List<ImageDeviceInfoJSON> images;

    @Schema(description = "The source identifier")
    public String getSourceID() {
        return sourceID;
    }

    public void setSourceID(String sourceID) {
        this.sourceID = sourceID;
    }

    @Schema(description = "Device information per forensic image (evidenceUUID)")
    public List<ImageDeviceInfoJSON> getImages() {
        return images;
    }

    public void setImages(List<ImageDeviceInfoJSON> images) {
        this.images = images;
    }
}
