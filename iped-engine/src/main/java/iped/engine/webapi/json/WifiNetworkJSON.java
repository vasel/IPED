package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A WiFi network entry with SSID and optional BSSID/security info.
 */
@Schema(name = "WifiNetwork")
public class WifiNetworkJSON {

    private String ssid;
    private String bssid;
    private String securityType;
    private int itemId;
    private String source; // "ufed" or "eventtranscript"

    public WifiNetworkJSON() {
    }

    public WifiNetworkJSON(String ssid, String bssid, String securityType, int itemId, String source) {
        this.ssid = ssid;
        this.bssid = bssid;
        this.securityType = securityType;
        this.itemId = itemId;
        this.source = source;
    }

    @Schema(description = "WiFi network SSID")
    public String getSsid() {
        return ssid;
    }

    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    @Schema(description = "WiFi network BSSID (MAC address)")
    public String getBssid() {
        return bssid;
    }

    public void setBssid(String bssid) {
        this.bssid = bssid;
    }

    @Schema(description = "Security type (e.g. WPA2, WEP)")
    public String getSecurityType() {
        return securityType;
    }

    public void setSecurityType(String securityType) {
        this.securityType = securityType;
    }

    @Schema(description = "IPED item ID of the WiFi network item")
    public int getItemId() {
        return itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    @Schema(description = "Data source: ufed or eventtranscript")
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
