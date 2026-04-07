package iped.engine.webapi.json;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A Bluetooth device found in the forensic image (from UFED extraction).
 */
@Schema(name = "BluetoothDevice")
public class BluetoothDeviceJSON {

    private String name;
    private String macAddress;
    private String alias;
    private String classOfDevice;
    private String lastUsedDate;
    private String status;
    private int itemId;

    public BluetoothDeviceJSON() {
    }

    public BluetoothDeviceJSON(String name, String macAddress, String alias,
            String classOfDevice, String lastUsedDate, String status, int itemId) {
        this.name = name;
        this.macAddress = macAddress;
        this.alias = alias;
        this.classOfDevice = classOfDevice;
        this.lastUsedDate = lastUsedDate;
        this.status = status;
        this.itemId = itemId;
    }

    @Schema(description = "Bluetooth device name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema(description = "Bluetooth MAC address")
    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    @Schema(description = "Bluetooth alias/friendly name")
    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    @Schema(description = "Bluetooth Class Of Device (e.g. Audio, Computer)")
    public String getClassOfDevice() {
        return classOfDevice;
    }

    public void setClassOfDevice(String classOfDevice) {
        this.classOfDevice = classOfDevice;
    }

    @Schema(description = "Last used/connected date")
    public String getLastUsedDate() {
        return lastUsedDate;
    }

    public void setLastUsedDate(String lastUsedDate) {
        this.lastUsedDate = lastUsedDate;
    }

    @Schema(description = "Connection status")
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Schema(description = "IPED item ID of the Bluetooth device item")
    public int getItemId() {
        return itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }
}
