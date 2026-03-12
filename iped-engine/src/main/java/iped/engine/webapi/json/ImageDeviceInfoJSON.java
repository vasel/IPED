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

    // Mac-specific top-level details (also useful for other desktop types)
    private String model;
    private String serialNumber;
    private String architecture;
    private String hostName;
    private String installDate;
    private List<String> users;
    private List<ReportRefJSON> connectedDevices;

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

    @Schema(description = "Hardware model (e.g., MacBookPro18,3)")
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    @Schema(description = "Serial number if found")
    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    @Schema(description = "CPU architecture, e.g., Intel or Apple Silicon")
    public String getArchitecture() {
        return architecture;
    }

    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    @Schema(description = "Host name (LocalHostName/ComputerName)")
    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    @Schema(description = "OS installation date (best-effort)")
    public String getInstallDate() {
        return installDate;
    }

    public void setInstallDate(String installDate) {
        this.installDate = installDate;
    }

    @Schema(description = "User accounts detected (e.g., /Users/*)")
    public List<String> getUsers() {
        return users;
    }

    public void setUsers(List<String> users) {
        this.users = users;
    }

    @Schema(description = "Connected devices references (e.g., sidebar lists, USB history)")
    public List<ReportRefJSON> getConnectedDevices() {
        return connectedDevices;
    }

    public void setConnectedDevices(List<ReportRefJSON> connectedDevices) {
        this.connectedDevices = connectedDevices;
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
