package iped.engine.webapi.json;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Device/system information for a single forensic image identified by evidenceUUID.
 */
@Schema(name = "ImageDeviceInfo")
public class ImageDeviceInfoJSON {

    private String evidenceUUID;
    private String detectedType; // "mobile", "windows", "other"

    // ---- Parsed top-level fields (extracted from reports) ----
    private String deviceName;
    private List<String> ipAddresses;
    private String osName;
    private String osVersion;
    private List<BluetoothDeviceJSON> bluetoothDevices;

    // ---- Source-specific detail ----
    private MobileInfoJSON mobileInfo;
    private DesktopInfoJSON desktopInfo;
    private List<WifiNetworkJSON> wifiNetworks;
    private List<ReportRefJSON> usbDeviceReports;
    private List<ReportRefJSON> otherInfo;

    @Schema(description = "Evidence UUID identifying this forensic image")
    public String getEvidenceUUID() {
        return evidenceUUID;
    }

    public void setEvidenceUUID(String evidenceUUID) {
        this.evidenceUUID = evidenceUUID;
    }

    @Schema(description = "Detected image type: mobile, windows, or other")
    public String getDetectedType() {
        return detectedType;
    }

    public void setDetectedType(String detectedType) {
        this.detectedType = detectedType;
    }

    @Schema(description = "Device/computer name on the network")
    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    @Schema(description = "IP addresses found for this device")
    public List<String> getIpAddresses() {
        return ipAddresses;
    }

    public void setIpAddresses(List<String> ipAddresses) {
        this.ipAddresses = ipAddresses;
    }

    @Schema(description = "Operating system name (e.g. Windows 10 Enterprise, Android)")
    public String getOsName() {
        return osName;
    }

    public void setOsName(String osName) {
        this.osName = osName;
    }

    @Schema(description = "Operating system version")
    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    @Schema(description = "Bluetooth devices found connected to this device")
    public List<BluetoothDeviceJSON> getBluetoothDevices() {
        return bluetoothDevices;
    }

    public void setBluetoothDevices(List<BluetoothDeviceJSON> bluetoothDevices) {
        this.bluetoothDevices = bluetoothDevices;
    }

    @Schema(description = "Mobile device information (UFED), present when detectedType=mobile")
    public MobileInfoJSON getMobileInfo() {
        return mobileInfo;
    }

    public void setMobileInfo(MobileInfoJSON mobileInfo) {
        this.mobileInfo = mobileInfo;
    }

    @Schema(description = "Desktop OS information, present when detectedType=windows")
    public DesktopInfoJSON getDesktopInfo() {
        return desktopInfo;
    }

    public void setDesktopInfo(DesktopInfoJSON desktopInfo) {
        this.desktopInfo = desktopInfo;
    }

    @Schema(description = "WiFi networks found in this image")
    public List<WifiNetworkJSON> getWifiNetworks() {
        return wifiNetworks;
    }

    public void setWifiNetworks(List<WifiNetworkJSON> wifiNetworks) {
        this.wifiNetworks = wifiNetworks;
    }

    @Schema(description = "USB device report references (Registry Device Info)")
    public List<ReportRefJSON> getUsbDeviceReports() {
        return usbDeviceReports;
    }

    public void setUsbDeviceReports(List<ReportRefJSON> usbDeviceReports) {
        this.usbDeviceReports = usbDeviceReports;
    }

    @Schema(description = "Other device information references")
    public List<ReportRefJSON> getOtherInfo() {
        return otherInfo;
    }

    public void setOtherInfo(List<ReportRefJSON> otherInfo) {
        this.otherInfo = otherInfo;
    }
}
