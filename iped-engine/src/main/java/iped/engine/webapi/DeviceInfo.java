package iped.engine.webapi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import iped.data.IIPEDSource;
import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.webapi.json.DesktopInfoJSON;
import iped.engine.webapi.json.DeviceInfoEntryJSON;
import iped.engine.webapi.json.DeviceInfoJSON;
import iped.engine.webapi.json.ImageDeviceInfoJSON;
import iped.engine.webapi.json.BluetoothDeviceJSON;
import iped.engine.webapi.json.MobileInfoJSON;
import iped.engine.webapi.json.ReportRefJSON;
import iped.engine.webapi.json.WifiNetworkJSON;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;
import iped.search.SearchResult;

@Tag(name = "Device Info")
@Path("sources/{sourceID}/deviceinfo")
public class DeviceInfo {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceInfo.class);

    /** Pre-computed cache: sourceID -> DeviceInfoJSON. Populated at startup or on first access. */
    private static final Map<String, DeviceInfoJSON> CACHE = new ConcurrentHashMap<>();

    private static final String TYPE_MOBILE = "mobile";
    private static final String TYPE_WINDOWS = "windows";
    private static final String TYPE_MAC = "mac";
    private static final String TYPE_OTHER = "other";

    // MIME types for detection
    private static final String UFED_DEVICE_INFO_MIME = "application/x-ufed-deviceinfo";
    private static final String REGISTRY_REPORT_MIME = "application/x-windows-registry-report";

    // WiFi MIME types
    private static final String UFED_WIRELESS_MIME = "application/x-ufed-wirelessnetwork";
    private static final String UFED_HTML_WIFI_MIME = "application/x-ufed-html-wifi";

    // EventTranscript networking MIME
    private static final String EVENT_TRANSCRIPT_NETWORKING_MIME = "application/x-event-transcript-networking";
    private static final String EVENT_TRANSCRIPT_NETWORKING_REG_MIME = "application/x-event-transcript-networking-registry";

    // Category names
    private static final String CAT_WIRELESS_NETWORKS = "Wireless Networks";
    private static final String CAT_DEVICE_INFORMATION = "Device Information";
    private static final String CAT_REGISTRY_OS_INFO = "Registry OS Info";
    private static final String CAT_REGISTRY_USER_ACCOUNTS = "Registry User Accounts";
    private static final String CAT_REGISTRY_DEVICE_INFO = "Registry Device Info";
    private static final String CAT_REGISTRY_NETWORK_INFO = "Registry Network Info";
    private static final String CAT_REGISTRY_STORAGE_INFO = "Registry Storage Info";

    // UFED metadata field prefixes
    private static final String UFED_SSID = ExtraProperties.UFED_META_PREFIX + "SSId";
    private static final String UFED_BSSID = ExtraProperties.UFED_META_PREFIX + "BSSId";
    private static final String UFED_SECURITY_TYPE = ExtraProperties.UFED_META_PREFIX + "SecurityType";

    // Bluetooth MIME types
    private static final String UFED_BLUETOOTH_MIME = "application/x-ufed-bluetoothdevice";
    private static final String UFED_HTML_BLUETOOTH_MIME = "application/x-ufed-html-bluetooth";
    private static final String CAT_BLUETOOTH_DEVICES = "Bluetooth Devices";

    // UFED Bluetooth metadata fields
    private static final String UFED_NAME = ExtraProperties.UFED_META_PREFIX + "Name";
    private static final String UFED_DEVICE_NAME = ExtraProperties.UFED_META_PREFIX + "DeviceName";
    private static final String UFED_MAC_ADDRESS = ExtraProperties.UFED_META_PREFIX + "MAC Address";
    private static final String UFED_BT_ALIAS = ExtraProperties.UFED_META_PREFIX + "Bluetooth alias:";
    private static final String UFED_BT_CLASS = ExtraProperties.UFED_META_PREFIX + "Bluetooth Class Of Device:";
    private static final String UFED_LAST_USED_DATE = ExtraProperties.UFED_META_PREFIX + "LastUsedDate";
    private static final String UFED_STATUS = ExtraProperties.UFED_META_PREFIX + "Status";

    /** Maximum time (seconds) allowed per source during precomputation. */
    private static final long PRECOMPUTE_TIMEOUT_SECS = 180;

    /**
     * Pre-compute device info for all sources. Call from Sources.init() at startup.
     * Each source is processed sequentially with a per-source timeout; total time is logged.
     */
    public static void precomputeAll() {
        if (Sources.multiSource == null || Sources.sourceIntToString == null) return;
        long start = System.currentTimeMillis();
        int count = 0;

        // Utilizamos um Executor próprio ao invés do ForkJoinPool.commonPool() 
        // para evitar perda de AccessControlContext quando executado com SecurityManager.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (Map.Entry<Integer, String> entry : Sources.sourceIntToString.entrySet()) {
                String sourceID = entry.getValue();
                try {
                    LOGGER.info("Precomputing deviceinfo for source '{}'...", sourceID);
                    long srcStart = System.currentTimeMillis();
                    DeviceInfoJSON info = CompletableFuture
                            .supplyAsync(() -> {
                                try {
                                    return computeDeviceInfo(sourceID);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }, executor)
                            .get(PRECOMPUTE_TIMEOUT_SECS, TimeUnit.SECONDS);
                    CACHE.put(sourceID, info);
                    long srcElapsed = System.currentTimeMillis() - srcStart;
                    LOGGER.info("Deviceinfo for '{}' precomputed in {}ms", sourceID, srcElapsed);
                    count++;
                } catch (TimeoutException te) {
                    LOGGER.warn("Precompute deviceinfo for '{}' timed out after {}s – will compute on demand",
                            sourceID, PRECOMPUTE_TIMEOUT_SECS);
                } catch (Exception e) {
                    LOGGER.warn("Failed to precompute deviceinfo for '{}': {}", sourceID,
                            e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("Precomputed deviceinfo for {} source(s) in {}ms", count, elapsed);
    }

    /**
     * Invalidate the entire device-info cache (e.g. on source reload).
     */
    public static void invalidateCache() {
        CACHE.clear();
    }

    @Operation(summary = "Get device/system information for each forensic image in this source")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DeviceInfoJSON getDeviceInfo(@PathParam("sourceID") String sourceID) throws Exception {
        // Return from cache if available; otherwise compute on the fly and cache
        DeviceInfoJSON cached = CACHE.get(sourceID);
        if (cached != null) {
            return cached;
        }
        DeviceInfoJSON result = computeDeviceInfo(sourceID);
        CACHE.put(sourceID, result);
        return result;
    }

    private static DeviceInfoJSON computeDeviceInfo(String sourceID) throws Exception {
        IIPEDSource source = Sources.getSource(sourceID);
        IPEDSource ipedSource = (IPEDSource) source;

        DeviceInfoJSON result = new DeviceInfoJSON();
        result.setSourceID(sourceID);

        Set<String> evidenceUUIDs = ipedSource.getEvidenceUUIDs();
        List<ImageDeviceInfoJSON> images = new ArrayList<>();

        DeviceInfo builder = new DeviceInfo();
        for (String uuid : evidenceUUIDs) {
            ImageDeviceInfoJSON imageInfo = builder.buildImageDeviceInfo(ipedSource, sourceID, uuid);
            images.add(imageInfo);
        }

        result.setImages(images);
        return result;
    }

    private ImageDeviceInfoJSON buildImageDeviceInfo(IPEDSource source, String sourceID, String uuid) throws Exception {
        ImageDeviceInfoJSON imageInfo = new ImageDeviceInfoJSON();
        imageInfo.setEvidenceUUID(uuid);

        // Detect image type
        String detectedType = detectImageType(source, uuid);
        imageInfo.setDetectedType(detectedType);

        // Collect data based on type
        switch (detectedType) {
            case TYPE_MOBILE:
                imageInfo.setMobileInfo(collectMobileInfo(source, sourceID, uuid));
                break;
            case TYPE_WINDOWS:
                imageInfo.setDesktopInfo(collectDesktopInfo(source, sourceID, uuid));
                break;
            case TYPE_MAC:
                collectMacInfo(imageInfo, source, sourceID, uuid);
                break;
            default:
                imageInfo.setOtherInfo(collectOtherInfo(source, sourceID, uuid));
                break;
        }

        // Collect WiFi networks (applicable to all types)
        imageInfo.setWifiNetworks(collectWifiNetworks(source, sourceID, uuid, detectedType));

        // Collect USB device reports (mainly for Windows, but try for all)
        imageInfo.setUsbDeviceReports(collectUsbDeviceReports(source, sourceID, uuid));

        // Collect Bluetooth devices
        imageInfo.setBluetoothDevices(collectBluetoothDevices(source, uuid));

        // Extract parsed top-level fields (device name, IP, OS) from already-collected data
        extractCommonFields(imageInfo, source, uuid);

        if (TYPE_MAC.equals(detectedType)) {
            extractMacFields(imageInfo, source, uuid);
        }

        return imageInfo;
    }

    /**
     * Extract macOS common fields after mac collection (host/device/OS).
     */
    private void extractMacFields(ImageDeviceInfoJSON imageInfo, IPEDSource source, String uuid) {
        // If we already have fields from mac collection, nothing else to do here for now.
    }

    /**
     * Detect the type of forensic image by checking for characteristic items.
     */
    private String detectImageType(IPEDSource source, String uuid) throws Exception {
        // Check for UFED device info → mobile
        String mobileQuery = BasicProps.CONTENTTYPE + ":\"" + UFED_DEVICE_INFO_MIME + "\" AND "
                + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
        if (countResults(source, mobileQuery) > 0) {
            return TYPE_MOBILE;
        }

        // Check for Windows registry reports → windows
        String windowsQuery = BasicProps.CONTENTTYPE + ":\"" + REGISTRY_REPORT_MIME + "\" AND "
            + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
        if (countResults(source, windowsQuery) > 0) {
            return TYPE_WINDOWS;
        }

        // Check for macOS SystemVersion.plist → mac
        String macQuery = BasicProps.NAME + ":\"SystemVersion.plist\" AND "
            + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
        if (countResults(source, macQuery) > 0) {
            return TYPE_MAC;
        }

        return TYPE_OTHER;
    }

    /**
     * Collect mobile device info from UFED Device Info items.
     */
    private MobileInfoJSON collectMobileInfo(IPEDSource source, String sourceID, String uuid) throws Exception {
        String query = BasicProps.CONTENTTYPE + ":\"" + UFED_DEVICE_INFO_MIME + "\" AND "
                + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
        SearchResult sr = searchItems(source, query);
        if (sr.getLength() == 0) {
            return null;
        }

        // Use the first device info item
        int itemId = sr.getId(0);
        IItem item = source.getItemByID(itemId);

        MobileInfoJSON mobileInfo = new MobileInfoJSON();
        mobileInfo.setDeviceInfoItemId(itemId);
        mobileInfo.setDeviceInfoContentUrl(buildContentUrl(sourceID, itemId));

        // Parse the HTML content to extract structured device properties
        List<DeviceInfoEntryJSON> properties = parseDeviceInfoHtml(item);
        mobileInfo.setDeviceProperties(properties);

        return mobileInfo;
    }

    /**
     * Collect desktop OS info from Windows registry reports.
     */
    private DesktopInfoJSON collectDesktopInfo(IPEDSource source, String sourceID, String uuid) throws Exception {
        DesktopInfoJSON desktopInfo = new DesktopInfoJSON();

        desktopInfo.setOsInfoReports(collectReportsByCategory(source, sourceID, uuid, CAT_REGISTRY_OS_INFO));
        desktopInfo.setUserReports(collectReportsByCategory(source, sourceID, uuid, CAT_REGISTRY_USER_ACCOUNTS));
        desktopInfo.setNetworkInfoReports(collectReportsByCategory(source, sourceID, uuid, CAT_REGISTRY_NETWORK_INFO));
        desktopInfo.setStorageInfoReports(collectReportsByCategory(source, sourceID, uuid, CAT_REGISTRY_STORAGE_INFO));

        return desktopInfo;
    }

    /**
     * Collect report references for items in a given category and evidenceUUID.
     */
    private List<ReportRefJSON> collectReportsByCategory(IPEDSource source, String sourceID, String uuid,
            String category) throws Exception {
        String query = BasicProps.CATEGORY + ":\"" + category + "\" AND "
                + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
        return searchAndMapToReportRefs(source, sourceID, query);
    }

    /**
     * Collect WiFi networks from both UFED and EventTranscript sources.
     */
    private List<WifiNetworkJSON> collectWifiNetworks(IPEDSource source, String sourceID, String uuid,
            String detectedType) throws Exception {
        List<WifiNetworkJSON> wifiNetworks = new ArrayList<>();

        // UFED Wireless Networks (mobile or any UFED source)
        String ufedWifiQuery = BasicProps.CATEGORY + ":\"" + CAT_WIRELESS_NETWORKS + "\" AND "
                + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
        SearchResult sr = searchItems(source, ufedWifiQuery);
        for (int i = 0; i < sr.getLength(); i++) {
            int itemId = sr.getId(i);
            int luceneId = source.getLuceneId(itemId);
            Document doc = source.getReader().document(luceneId);

            String ssid = getDocField(doc, UFED_SSID);
            String bssid = getDocField(doc, UFED_BSSID);
            String securityType = getDocField(doc, UFED_SECURITY_TYPE);

            if (ssid != null || bssid != null) {
                wifiNetworks.add(new WifiNetworkJSON(ssid, bssid, securityType, itemId, "ufed"));
            }
        }

        // EventTranscript Networking (Windows)
        if (TYPE_WINDOWS.equals(detectedType)) {
            String etQuery = "(" + BasicProps.CONTENTTYPE + ":\"" + EVENT_TRANSCRIPT_NETWORKING_MIME + "\" OR "
                    + BasicProps.CONTENTTYPE + ":\"" + EVENT_TRANSCRIPT_NETWORKING_REG_MIME + "\") AND "
                    + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
            SearchResult etSr = searchItems(source, etQuery);
            for (int i = 0; i < etSr.getLength(); i++) {
                int itemId = etSr.getId(i);
                int luceneId = source.getLuceneId(itemId);
                Document doc = source.getReader().document(luceneId);

                String name = getDocField(doc, BasicProps.NAME);
                wifiNetworks.add(new WifiNetworkJSON(name, null, null, itemId, "eventtranscript"));
            }
        }

        return wifiNetworks;
    }

    /**
     * Collect Bluetooth devices from UFED extraction.
     */
    private List<BluetoothDeviceJSON> collectBluetoothDevices(IPEDSource source, String uuid) throws Exception {
        List<BluetoothDeviceJSON> devices = new ArrayList<>();

        String query = "(" + BasicProps.CONTENTTYPE + ":\"" + UFED_BLUETOOTH_MIME + "\" OR "
                + BasicProps.CONTENTTYPE + ":\"" + UFED_HTML_BLUETOOTH_MIME + "\") AND "
                + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";

        // Also try by category (covers both MIME variants)
        String catQuery = BasicProps.CATEGORY + ":\"" + CAT_BLUETOOTH_DEVICES + "\" AND "
                + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";

        // Use the broader category query
        SearchResult sr = searchItems(source, catQuery);
        if (sr.getLength() == 0) {
            sr = searchItems(source, query);
        }

        for (int i = 0; i < sr.getLength(); i++) {
            int itemId = sr.getId(i);
            int luceneId = source.getLuceneId(itemId);
            Document doc = source.getReader().document(luceneId);

            String name = firstNonNull(getDocField(doc, UFED_NAME), getDocField(doc, UFED_DEVICE_NAME),
                    getDocField(doc, BasicProps.NAME));
            String macAddress = getDocField(doc, UFED_MAC_ADDRESS);
            String alias = getDocField(doc, UFED_BT_ALIAS);
            String classOfDevice = getDocField(doc, UFED_BT_CLASS);
            String lastUsed = getDocField(doc, UFED_LAST_USED_DATE);
            String status = getDocField(doc, UFED_STATUS);

            if (name != null || macAddress != null) {
                devices.add(new BluetoothDeviceJSON(name, macAddress, alias, classOfDevice, lastUsed, status, itemId));
            }
        }

        return devices;
    }

    /**
     * Extract common/parsed top-level fields from already-collected data.
     * For mobile: from UFED DeviceInfo property entries.
     * For Windows: parsed from registry report HTML content.
     */
    private void extractCommonFields(ImageDeviceInfoJSON imageInfo, IPEDSource source, String uuid) {
        switch (imageInfo.getDetectedType()) {
            case TYPE_MOBILE:
                extractMobileCommonFields(imageInfo);
                break;
            case TYPE_WINDOWS:
                extractWindowsCommonFields(imageInfo, source, uuid);
                break;
            default:
                break;
        }
    }

    /**
     * Extract device name, IP, OS from the parsed UFED DeviceInfo property entries.
     */
    private void extractMobileCommonFields(ImageDeviceInfoJSON imageInfo) {
        MobileInfoJSON mobileInfo = imageInfo.getMobileInfo();
        if (mobileInfo == null || mobileInfo.getDeviceProperties() == null) {
            return;
        }

        List<DeviceInfoEntryJSON> props = mobileInfo.getDeviceProperties();
        List<String> ips = new ArrayList<>();

        for (DeviceInfoEntryJSON entry : props) {
            String key = entry.getName().toLowerCase().trim();
            String val = entry.getValue();
            if (val == null || val.isEmpty()) continue;

            // Device name detection
            if (imageInfo.getDeviceName() == null) {
                if (key.equals("devicename") || key.equals("device name")
                        || key.equals("hostname") || key.equals("computer name")
                        || key.equals("computername") || key.equals("devicehostname")) {
                    imageInfo.setDeviceName(val);
                }
            }

            // IP address detection
            if (key.contains("ip address") || key.equals("ipaddress")
                    || key.equals("last ip address") || key.equals("lastipaddress")) {
                ips.add(val);
            }

            // OS name/version detection
            if (imageInfo.getOsName() == null) {
                if (key.equals("device os") || key.equals("deviceos")
                        || key.equals("operating system") || key.equals("os")
                        || key.equals("platform")) {
                    imageInfo.setOsName(val);
                }
            }
            if (imageInfo.getOsVersion() == null) {
                if (key.equals("os version") || key.equals("osversion")
                        || key.equals("software version") || key.equals("softwareversion")
                        || key.equals("firmware version") || key.equals("firmwareversion")
                        || key.equals("device os version")) {
                    imageInfo.setOsVersion(val);
                }
            }
        }

        if (!ips.isEmpty()) {
            imageInfo.setIpAddresses(ips);
        }
    }

    /**
     * Extract device name, IP, OS from Windows registry report HTML content.
     * Parses RegRipper-generated HTML reports for key-value patterns.
     */
    private void extractWindowsCommonFields(ImageDeviceInfoJSON imageInfo, IPEDSource source, String uuid) {
        DesktopInfoJSON desktopInfo = imageInfo.getDesktopInfo();
        if (desktopInfo == null) {
            return;
        }

        List<String> ips = new ArrayList<>();

        // Parse OS Info reports for ComputerName, ProductName, CurrentVersion etc.
        if (desktopInfo.getOsInfoReports() != null) {
            for (ReportRefJSON report : desktopInfo.getOsInfoReports()) {
                try {
                    IItem item = source.getItemByID(report.getId());
                    if (item == null) continue;
                    String html = readItemContent(item);
                    if (html == null) continue;

                    parseRegistryReportFields(html, imageInfo, ips);
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse OS Info report {}: {}", report.getName(), e.getMessage());
                }
            }
        }

        // Parse Network Info reports for IP addresses
        if (desktopInfo.getNetworkInfoReports() != null) {
            for (ReportRefJSON report : desktopInfo.getNetworkInfoReports()) {
                try {
                    IItem item = source.getItemByID(report.getId());
                    if (item == null) continue;
                    String html = readItemContent(item);
                    if (html == null) continue;

                    parseNetworkReportForIPs(html, ips);
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse Network Info report {}: {}", report.getName(), e.getMessage());
                }
            }
        }

        if (!ips.isEmpty()) {
            // Deduplicate preserving order
            imageInfo.setIpAddresses(new ArrayList<>(new LinkedHashSet<>(ips)));
        }
    }

    // Regex patterns for Windows registry report parsing
    private static final Pattern RR_COMPUTER_NAME = Pattern.compile(
            "(?:ComputerName|Computer\\s*Name)\\s*[:=]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RR_PRODUCT_NAME = Pattern.compile(
            "ProductName\\s*[:=]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RR_CURRENT_VERSION = Pattern.compile(
            "(?:CurrentVersion|CurrentBuildNumber|CurrentBuild)\\s*[:=]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RR_DISPLAY_VERSION = Pattern.compile(
            "DisplayVersion\\s*[:=]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RR_IP_ADDRESS = Pattern.compile(
            "(?:IPAddress|DhcpIPAddress|IP\\s*Address)\\s*[:=]\\s*([\\d]+\\.[\\d]+\\.[\\d]+\\.[\\d]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse a RegRipper OS report for ComputerName, ProductName, Version.
     */
    private void parseRegistryReportFields(String html, ImageDeviceInfoJSON imageInfo, List<String> ips) {
        // Strip HTML tags for easier regex matching
        String text = stripHtmlTags(html);

        if (imageInfo.getDeviceName() == null) {
            Matcher m = RR_COMPUTER_NAME.matcher(text);
            if (m.find()) {
                imageInfo.setDeviceName(m.group(1).trim());
            }
        }

        if (imageInfo.getOsName() == null) {
            Matcher m = RR_PRODUCT_NAME.matcher(text);
            if (m.find()) {
                imageInfo.setOsName(m.group(1).trim());
            }
        }

        if (imageInfo.getOsVersion() == null) {
            // Try DisplayVersion first (e.g. "22H2"), then CurrentBuildNumber
            Matcher m = RR_DISPLAY_VERSION.matcher(text);
            if (m.find()) {
                imageInfo.setOsVersion(m.group(1).trim());
            } else {
                m = RR_CURRENT_VERSION.matcher(text);
                if (m.find()) {
                    imageInfo.setOsVersion(m.group(1).trim());
                }
            }
        }

        // Also look for IPs in OS reports
        Matcher ipMatcher = RR_IP_ADDRESS.matcher(text);
        while (ipMatcher.find()) {
            String ip = ipMatcher.group(1).trim();
            if (!ip.equals("0.0.0.0") && !ip.equals("255.255.255.255")) {
                ips.add(ip);
            }
        }
    }

    /**
     * Parse a RegRipper Network report for IP addresses.
     */
    private void parseNetworkReportForIPs(String html, List<String> ips) {
        String text = stripHtmlTags(html);
        Matcher ipMatcher = RR_IP_ADDRESS.matcher(text);
        while (ipMatcher.find()) {
            String ip = ipMatcher.group(1).trim();
            if (!ip.equals("0.0.0.0") && !ip.equals("255.255.255.255")) {
                ips.add(ip);
            }
        }
    }

    /** Maximum content size we'll read for parsing (1 MB). Prevents blocking on huge files. */
    private static final int MAX_CONTENT_READ = 1024 * 1024;

    /**
     * Read the content of an item as a String, capped at {@link #MAX_CONTENT_READ} bytes.
     */
    private String readItemContent(IItem item) {
        try (InputStream is = item.getBufferedInputStream()) {
            if (is == null) return null;
            return readInputStream(is, MAX_CONTENT_READ);
        } catch (IOException e) {
            LOGGER.warn("Failed to read content of item {}: {}", item.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Parse a plist file (binary or XML) into an NSDictionary using the dd-plist library.
     * Returns null if the item cannot be parsed or is not a dictionary.
     */
    private NSDictionary parsePlistDict(IItem item) {
        try (InputStream is = item.getBufferedInputStream()) {
            if (is == null) return null;
            NSObject obj = PropertyListParser.parse(is);
            if (obj instanceof NSDictionary) {
                return (NSDictionary) obj;
            }
            LOGGER.debug("Plist {} is not a dictionary (type={})", item.getName(),
                    obj != null ? obj.getClass().getSimpleName() : "null");
        } catch (Exception e) {
            LOGGER.warn("Failed to parse plist {}: {}", item.getName(), e.getMessage());
        }
        return null;
    }

    /**
     * Get a string value from an NSDictionary. Returns null if key is absent.
     */
    private String dictStringValue(NSDictionary dict, String key) {
        if (dict == null || key == null) return null;
        NSObject obj = dict.get(key);
        if (obj == null) return null;
        if (obj instanceof NSString) return ((NSString) obj).getContent();
        // For numbers/booleans, use toString()
        return obj.toString();
    }

    /**
     * Strip HTML tags from a string, leaving plain text.
     */
    private String stripHtmlTags(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
    }

    /**
     * Return the first non-null value from the given arguments.
     */
    private String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        return null;
    }

    /**
     * Collect USB device report references.
     */
    private List<ReportRefJSON> collectUsbDeviceReports(IPEDSource source, String sourceID, String uuid)
            throws Exception {
        return collectReportsByCategory(source, sourceID, uuid, CAT_REGISTRY_DEVICE_INFO);
    }

    /**
     * Collect other device information for non-mobile, non-Windows images.
     */
    private List<ReportRefJSON> collectOtherInfo(IPEDSource source, String sourceID, String uuid) throws Exception {
        // Try UFED device information (generic)
        String query = BasicProps.CATEGORY + ":\"" + CAT_DEVICE_INFORMATION + "\" AND "
                + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
        return searchAndMapToReportRefs(source, sourceID, query);
    }

    /**
     * Collect macOS device information from common plist files.
     * Uses dd-plist library to parse both binary and XML plists, with regex fallback.
     */
    private void collectMacInfo(ImageDeviceInfoJSON imageInfo, IPEDSource source, String sourceID, String uuid)
            throws Exception {
        // SystemVersion.plist for OS version/name/build and install date (best-effort)
        IItem systemVersionItem = findFirstByName(source, uuid, "SystemVersion.plist");
        if (systemVersionItem != null) {
            // Try binary/XML plist parsing first, fall back to regex on raw text
            NSDictionary svDict = parsePlistDict(systemVersionItem);
            String productName = null, productVersion = null, buildVersion = null;
            if (svDict != null) {
                productName = dictStringValue(svDict, "ProductName");
                productVersion = dictStringValue(svDict, "ProductVersion");
                buildVersion = dictStringValue(svDict, "ProductBuildVersion");
                LOGGER.debug("SystemVersion.plist parsed via dd-plist: name={}, version={}, build={}",
                        productName, productVersion, buildVersion);
            } else {
                // Fallback: try reading as text (works for XML plists)
                String plist = readItemContent(systemVersionItem);
                if (plist != null) {
                    productName = plistValue(plist, "ProductName");
                    productVersion = plistValue(plist, "ProductVersion");
                    buildVersion = plistValue(plist, "ProductBuildVersion");
                    LOGGER.debug("SystemVersion.plist parsed via regex fallback: name={}, version={}, build={}",
                            productName, productVersion, buildVersion);
                }
            }

            imageInfo.setOsName(firstNonNull(imageInfo.getOsName(), productName));
            String combinedVersion = productVersion;
            if (productVersion != null && buildVersion != null) {
                combinedVersion = productVersion + " (" + buildVersion + ")";
            } else if (buildVersion != null) {
                combinedVersion = buildVersion;
            }
            imageInfo.setOsVersion(firstNonNull(imageInfo.getOsVersion(), combinedVersion));

            // Use file created date as install date hint
            int luceneId = source.getLuceneId(systemVersionItem.getId());
            Document doc = source.getReader().document(luceneId);
            String created = getDocField(doc, BasicProps.CREATED);
            imageInfo.setInstallDate(firstNonNull(imageInfo.getInstallDate(), created));

            // Add reference
            imageInfo.setOtherInfo(appendRef(imageInfo.getOtherInfo(),
                    new ReportRefJSON(systemVersionItem.getId(), systemVersionItem.getName(),
                            buildContentUrl(sourceID, systemVersionItem.getId()))));
        }

        // preferences.plist for ComputerName, HostName, LocalHostName, model/serial/arch hints
        IItem prefs = findFirstByName(source, uuid, "preferences.plist");
        if (prefs != null) {
            NSDictionary prefsDict = parsePlistDict(prefs);
            String computerName = null, hostName = null, localHostName = null;
            String model = null, serial = null, arch = null;
            if (prefsDict != null) {
                computerName = dictStringValue(prefsDict, "ComputerName");
                hostName = dictStringValue(prefsDict, "HostName");
                localHostName = dictStringValue(prefsDict, "LocalHostName");
                model = firstNonNull(dictStringValue(prefsDict, "HWModelString"),
                        dictStringValue(prefsDict, "Model"));
                serial = dictStringValue(prefsDict, "IOPlatformSerialNumber");
                arch = detectArchitectureFromDict(prefsDict);
                LOGGER.debug("preferences.plist parsed via dd-plist: computer={}, host={}, model={}",
                        computerName, hostName, model);
            } else {
                // Fallback: try reading as text
                String plist = readItemContent(prefs);
                if (plist != null) {
                    computerName = plistValue(plist, "ComputerName");
                    hostName = plistValue(plist, "HostName");
                    localHostName = plistValue(plist, "LocalHostName");
                    model = firstNonNull(plistValue(plist, "HWModelString"), plistValue(plist, "Model"));
                    serial = plistValue(plist, "IOPlatformSerialNumber");
                    arch = detectArchitecture(plist);
                }
            }

            imageInfo.setDeviceName(firstNonNull(imageInfo.getDeviceName(), computerName, localHostName));
            imageInfo.setHostName(firstNonNull(imageInfo.getHostName(), hostName, localHostName, computerName));
            imageInfo.setModel(firstNonNull(imageInfo.getModel(), model));
            imageInfo.setSerialNumber(firstNonNull(imageInfo.getSerialNumber(), serial));
            imageInfo.setArchitecture(firstNonNull(imageInfo.getArchitecture(), arch));

            imageInfo.setOtherInfo(appendRef(imageInfo.getOtherInfo(),
                    new ReportRefJSON(prefs.getId(), prefs.getName(),
                            buildContentUrl(sourceID, prefs.getId()))));
        }

        // Users list from /Users/* directories
        List<String> users = collectUsers(source, uuid);
        if (!users.isEmpty()) {
            imageInfo.setUsers(users);
        }

        // Connected devices: com.apple.sidebarlists.plist
        List<ReportRefJSON> sidebarRefs = collectByName(source, sourceID, uuid, "com.apple.sidebarlists.plist");
        if (!sidebarRefs.isEmpty()) {
            imageInfo.setConnectedDevices(sidebarRefs);
        }
    }

    private IItem findFirstByName(IPEDSource source, String uuid, String name) throws Exception {
        String query = BasicProps.NAME + ":\"" + name + "\" AND " + BasicProps.EVIDENCE_UUID + ":\"" + uuid
                + "\"";
        SearchResult sr = searchItems(source, query);
        if (sr.getLength() == 0) {
            return null;
        }
        return source.getItemByID(sr.getId(0));
    }

    private List<ReportRefJSON> collectByName(IPEDSource source, String sourceID, String uuid, String name)
            throws Exception {
        String query = BasicProps.NAME + ":\"" + name + "\" AND " + BasicProps.EVIDENCE_UUID + ":\"" + uuid
                + "\"";
        return searchAndMapToReportRefs(source, sourceID, query);
    }

    private String plistValue(String plist, String key) {
        if (plist == null || key == null) {
            return null;
        }

        String escapedKey = Pattern.quote(key);
        Pattern xmlPattern = Pattern.compile("<key>\\s*" + escapedKey
                + "\\s*</key>\\s*<(string|integer|real|date)>([^<]+)</\\1>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = xmlPattern.matcher(plist);
        if (m.find()) {
            return m.group(2).trim();
        }

        Pattern asciiPattern = Pattern.compile(escapedKey + "\\s*=\\s*\"([^\"]+)\";",
                Pattern.CASE_INSENSITIVE);
        m = asciiPattern.matcher(plist);
        if (m.find()) {
            return m.group(1).trim();
        }

        Pattern barePattern = Pattern.compile(escapedKey + "\\s*=\\s*([^;\\r\\n]+)", Pattern.CASE_INSENSITIVE);
        m = barePattern.matcher(plist);
        if (m.find()) {
            return m.group(1).trim();
        }

        return null;
    }

    private String detectArchitecture(String plist) {
        if (plist == null) {
            return null;
        }

        String arch = firstNonNull(plistValue(plist, "Architecture"), plistValue(plist, "arch"));
        if (arch != null) {
            return arch;
        }

        String lower = plist.toLowerCase();
        if (lower.contains("arm64") || lower.contains("apple m1") || lower.contains("apple m2")
                || lower.contains("apple silicon")) {
            return "arm64";
        }
        if (lower.contains("x86_64") || lower.contains("intel")) {
            return "x86_64";
        }
        return null;
    }

    /**
     * Detect architecture from a parsed NSDictionary (binary or XML plist).
     */
    private String detectArchitectureFromDict(NSDictionary dict) {
        if (dict == null) return null;
        String arch = firstNonNull(dictStringValue(dict, "Architecture"), dictStringValue(dict, "arch"));
        if (arch != null) return arch;
        // Check all string values for architecture hints
        String allText = dict.toXMLPropertyList();
        if (allText != null) {
            String lower = allText.toLowerCase();
            if (lower.contains("arm64") || lower.contains("apple m1") || lower.contains("apple m2")
                    || lower.contains("apple silicon")) {
                return "arm64";
            }
            if (lower.contains("x86_64") || lower.contains("intel")) {
                return "x86_64";
            }
        }
        return null;
    }

    private List<String> collectUsers(IPEDSource source, String uuid) throws Exception {
        // The path field is tokenized, so a path like "/device/vol/Users/john" is stored as
        // individual tokens: "device", "vol", "users", "john". We search for directories
        // whose path contains the "Users" token, then filter in Java to extract usernames.
        // Using a phrase query "Users" matches the token directly.
        String query = BasicProps.ISDIR + ":true AND "
            + BasicProps.PATH + ":Users AND "
            + BasicProps.EVIDENCE_UUID + ":\"" + uuid + "\"";
        SearchResult sr = searchItems(source, query);
        LOGGER.debug("collectUsers query returned {} results for uuid {}", sr.getLength(), uuid);
        LinkedHashSet<String> users = new LinkedHashSet<>();

        for (int i = 0; i < sr.getLength(); i++) {
            int itemId = sr.getId(i);
            int luceneId = source.getLuceneId(itemId);
            Document doc = source.getReader().document(luceneId);
            String path = getDocField(doc, BasicProps.PATH);
            LOGGER.debug("collectUsers candidate path: {}", path);
            String user = extractUserFromPath(path);
            if (user != null) {
                users.add(user);
            }
        }

        LOGGER.debug("collectUsers found {} users: {}", users.size(), users);
        return new ArrayList<>(users);
    }

    private String extractUserFromPath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.indexOf("/Users/");
        if (idx == -1) {
            if (normalized.startsWith("Users/")) {
                idx = 0;
            } else {
                return null;
            }
        }

        String remainder = normalized.substring(idx + "/Users/".length());
        if (remainder.isEmpty()) {
            return null;
        }
        int slash = remainder.indexOf('/');
        String user = (slash >= 0) ? remainder.substring(0, slash) : remainder;
        if (user.isEmpty()) {
            return null;
        }
        if ("Shared".equalsIgnoreCase(user)) {
            return null;
        }
        return user;
    }

    private List<ReportRefJSON> appendRef(List<ReportRefJSON> refs, ReportRefJSON ref) {
        if (ref == null) {
            return refs;
        }
        List<ReportRefJSON> list = (refs != null) ? new ArrayList<>(refs) : new ArrayList<>();
        list.add(ref);
        return list;
    }

    // ---- Helper methods ----

    /**
     * Count results for a Lucene query string.
     */
    private int countResults(IPEDSource source, String queryStr) throws Exception {
        IPEDSearcher searcher = new IPEDSearcher(source, queryStr);
        searcher.setNoScoring(true);
        return searcher.count();
    }

    /**
     * Execute a search and return the SearchResult.
     */
    private SearchResult searchItems(IPEDSource source, String queryStr) throws Exception {
        IPEDSearcher searcher = new IPEDSearcher(source, queryStr);
        searcher.setNoScoring(true);
        return searcher.search();
    }

    /**
     * Search items and map results to ReportRefJSON list.
     */
    private List<ReportRefJSON> searchAndMapToReportRefs(IPEDSource source, String sourceID, String queryStr)
            throws Exception {
        SearchResult sr = searchItems(source, queryStr);
        List<ReportRefJSON> refs = new ArrayList<>();
        for (int i = 0; i < sr.getLength(); i++) {
            int itemId = sr.getId(i);
            int luceneId = source.getLuceneId(itemId);
            Document doc = source.getReader().document(luceneId);
            String name = getDocField(doc, BasicProps.NAME);
            refs.add(new ReportRefJSON(itemId, name, buildContentUrl(sourceID, itemId)));
        }
        return refs;
    }

    /**
     * Build the URL to fetch an item's content via the web API.
     */
    private String buildContentUrl(String sourceID, int itemId) {
        return "/sources/" + sourceID + "/docs/" + itemId + "/content";
    }

    /**
     * Get a single field value from a Lucene document.
     */
    private String getDocField(Document doc, String fieldName) {
        String val = doc.get(fieldName);
        return (val != null && !val.isEmpty()) ? val : null;
    }

    /**
     * Parse the UFED Device Info HTML table to extract structured name/value/extraction entries.
     * The HTML contains rows with 3 columns: property name, value, extraction name.
     */
    private List<DeviceInfoEntryJSON> parseDeviceInfoHtml(IItem item) {
        List<DeviceInfoEntryJSON> entries = new ArrayList<>();
        try (InputStream is = item.getBufferedInputStream()) {
            if (is == null) {
                return entries;
            }
            String html = readInputStream(is);

            // Parse table rows: <tr><td class="a">name</td><td class="b">value</td><td class="c">extraction</td></tr>
            Pattern rowPattern = Pattern.compile(
                    "<tr>\\s*<td[^>]*>([^<]*)</td>\\s*<td[^>]*>([^<]*)</td>\\s*<td[^>]*>([^<]*)</td>\\s*</tr>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = rowPattern.matcher(html);
            while (matcher.find()) {
                String name = decodeHtmlEntities(matcher.group(1).trim());
                String value = decodeHtmlEntities(matcher.group(2).trim());
                String extraction = decodeHtmlEntities(matcher.group(3).trim());
                if (!name.isEmpty() && !value.isEmpty()) {
                    entries.add(new DeviceInfoEntryJSON(name, value, extraction));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to parse Device Info HTML for item {}: {}", item.getName(), e.getMessage());
        }
        return entries;
    }

    private String readInputStream(InputStream is) throws IOException {
        return readInputStream(is, Integer.MAX_VALUE);
    }

    private String readInputStream(InputStream is, int maxBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int totalRead = 0;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
                totalRead += read;
                if (totalRead >= maxBytes) break;
            }
        }
        return sb.toString();
    }

    private String decodeHtmlEntities(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
