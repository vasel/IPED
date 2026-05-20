package iped.engine.webapi.json;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Desktop OS information collected from Windows registry reports.
 */
@Schema(name = "DesktopInfo")
public class DesktopInfoJSON {

    private List<ReportRefJSON> osInfoReports;
    private List<ReportRefJSON> userReports;
    private List<ReportRefJSON> networkInfoReports;
    private List<ReportRefJSON> storageInfoReports;

    private String rawRegistryReport;

    @Schema(description = "OS info report references (Registry OS Info)")
    public List<ReportRefJSON> getOsInfoReports() {
        return osInfoReports;
    }

    public void setOsInfoReports(List<ReportRefJSON> osInfoReports) {
        this.osInfoReports = osInfoReports;
    }

    @Schema(description = "User account report references (Registry User Accounts)")
    public List<ReportRefJSON> getUserReports() {
        return userReports;
    }

    public void setUserReports(List<ReportRefJSON> userReports) {
        this.userReports = userReports;
    }

    @Schema(description = "Network info report references (Registry Network Info)")
    public List<ReportRefJSON> getNetworkInfoReports() {
        return networkInfoReports;
    }

    public void setNetworkInfoReports(List<ReportRefJSON> networkInfoReports) {
        this.networkInfoReports = networkInfoReports;
    }

    @Schema(description = "Storage info report references (Registry Storage Info)")
    public List<ReportRefJSON> getStorageInfoReports() {
        return storageInfoReports;
    }

    public void setStorageInfoReports(List<ReportRefJSON> storageInfoReports) {
        this.storageInfoReports = storageInfoReports;
    }

    @Schema(description = "Raw registry report text for parsing in the frontend")
    public String getRawRegistryReport() {
        return rawRegistryReport;
    }

    public void setRawRegistryReport(String rawRegistryReport) {
        this.rawRegistryReport = rawRegistryReport;
    }
}
