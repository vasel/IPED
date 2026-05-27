package iped.engine.webapi;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.engine.task.ThumbTask;
import iped.engine.util.Util;
import iped.io.URLUtil;
import iped.parsers.util.PDFToThumb;
import iped.properties.BasicProps;
import iped.utils.ImageUtil;
import iped.viewers.util.LibreOfficeFinder;

public class ThumbnailProcessor implements Runnable {

    private static final int THUMB_SIZE = 480;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;
    private static final int MIN_PDF_SIZE = 67; // smallest valid PDF is ~67 bytes
    private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F', '-' };
    private static final Logger LOGGER = LoggerFactory.getLogger(ThumbnailProcessor.class);


    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String BOLD = "\033[1m";

    private static final Set<String> PDF_EXTENSIONS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("pdf")));

    private static final Set<String> IMAGE_EXTENSIONS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "wbmp", "tif", "tiff")));

    private static final Set<String> LIBREOFFICE_EXTENSIONS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                // Writer / text documents
                "doc", "docx", "docm", "dot", "dotx", "dotm", "rtf",
                "odt", "ott", "fodt", "sxw", "stw",
                "wps", "wpt", "wri", "wpd", "abw", "zabw", "hwp", "lwp",
                "mw", "mcw", "mwd", "wn", "602", "sdw", "pages",
                "txt", "csv", "tsv", "tab", "md", "markdown",

                // Calc / spreadsheets
                "xls", "xlsx", "xlsm", "xlsb", "xlw", "xlc", "xlm",
                "xlt", "xltx", "xltm", "ods", "ots", "fods", "sxc", "stc",
                "wk1", "wks", "123", "wb1", "wb2", "wq1", "wq2", "dif",
                "slk", "sylk", "gnumeric", "gnm", "numbers", "dbf", "parquet",
                "sdc", "uos",

                // Impress / presentations
                "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "potm",
                "odp", "otp", "fodp", "sxi", "sti", "key", "dps", "dpt", "sdd", "uop",

                // Draw / diagrams / desktop publishing
                "odg", "otg", "fodg", "sxd", "std",
                "vsd", "vdx", "vsdm", "vsdx", "vstx", "pub",
                "cdr", "cmx", "fh", "fh1", "fh2", "fh3", "fh4", "fh5", "fh6", "fh7", "fh8", "fh9", "fh10", "fh11",
                "p65", "pm", "pm6", "pmd", "qxd", "qxt", "zmf", "wpg",
                "pcx", "dxf", "ps", "eps"
            )));

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Thread runningThread;
    private static final AtomicReference<StatusSnapshot> STATUS =
            new AtomicReference<StatusSnapshot>(StatusSnapshot.idle());

    private static volatile String libreOfficePath;
    private static volatile boolean libreOfficePathResolved;

    private final Set<String> requestedExtensions;
    private final boolean force;

    public ThumbnailProcessor(Set<String> requestedExtensions, boolean force) {
        this.requestedExtensions = Collections.unmodifiableSet(new LinkedHashSet<String>(requestedExtensions));
        this.force = force;
    }

    public static boolean isBusy() {
        return RUNNING.get();
    }

    public static void cancelRunning() {
        Thread thread = runningThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public static StatusSnapshot getStatusSnapshot() {
        return STATUS.get();
    }

    public static Set<String> getSupportedExtensions() {
        LinkedHashSet<String> supported = new LinkedHashSet<String>();
        supported.addAll(PDF_EXTENSIONS);
        supported.addAll(IMAGE_EXTENSIONS);
        if (isLibreOfficeAvailable()) {
            supported.addAll(LIBREOFFICE_EXTENSIONS);
        }
        return Collections.unmodifiableSet(supported);
    }

    public static void printHelp() {
        System.out.println("\nUsage: process thumbnails [OPTIONS] <ext1> [ext2] ...");
        System.out.println();
        System.out.println("Generate thumbnails in background for items with the given file extensions.");
        System.out.println("Existing thumbnails are skipped unless --force or -force is used.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --force, -force    Regenerate thumbnails even if one already exists");
        System.out.println();
        System.out.println("Supported extensions:");
        System.out.println("  PDF:      pdf");
        System.out.println("  Images:   jpg, jpeg, png, gif, bmp, wbmp, tif, tiff");
        if (isLibreOfficeAvailable()) {
            System.out.println("  Office/Text: doc, docx, docm, dot, dotx, dotm, rtf, odt, ott, fodt, sxw, wps, wri, wpd, abw, hwp, lwp, txt, csv, tsv, tab, md");
            System.out.println("  Sheets:      xls, xlsx, xlsm, xlsb, xlt, xltx, xltm, ods, ots, fods, sxc, wk1, wks, 123, dif, slk, sylk, gnumeric, gnm, numbers, dbf");
            System.out.println("  Slides:      ppt, pptx, pptm, pps, ppsx, pot, potx, potm, odp, otp, fodp, sxi, key");
            System.out.println("  Draw/Other:  odg, fodg, vsd, vdx, vsdm, vsdx, vstx, pub, cdr, cmx, wpg, pcx, dxf, ps, eps");
        } else {
            System.out.println("  Office:   unavailable (LibreOffice not found)");
        }
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  process thumbnails pdf");
        System.out.println("  process thumbnails pptx docx pdf");
        System.out.println("  process thumbnails --force pdf");
    }

    @Override
    public void run() {
        if (!RUNNING.compareAndSet(false, true)) {
            System.out.println(RED + "A thumbnail process is already running." + RESET);
            return;
        }

        long startedAt = System.currentTimeMillis();
        STATUS.set(StatusSnapshot.running(requestedExtensions, force, startedAt));
        runningThread = Thread.currentThread();

        try {
            processAll();
            STATUS.set(STATUS.get().finish(false, "finished"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            STATUS.set(STATUS.get().finish(true, "cancelled"));
            System.out.println(YELLOW + "\nThumbnail process cancelled." + RESET);
        } catch (Exception e) {
            STATUS.set(STATUS.get().fail(e.getMessage()));
            System.out.println(RED + "\nThumbnail process failed: " + e.getMessage() + RESET);
        } finally {
            runningThread = null;
            RUNNING.set(false);
        }
    }

    private void processAll() throws Exception {
        if (Sources.multiSource == null) {
            throw new IOException("No sources loaded");
        }

        List<IPEDSource> sources = Sources.multiSource.getAtomicSources();

        System.out.println(BOLD + "\n=== Thumbnail Generation ===" + RESET);
        System.out.println("Extensions: " + requestedExtensions);
        System.out.println("Force:      " + force);
        System.out.println("Sources:    " + sources.size());
        System.out.println();

        for (IPEDSource source : sources) {
            ensureNotInterrupted();

            int srcId = source.getSourceId();
            String externalId = Sources.sourceIntToString != null
                    ? Sources.sourceIntToString.getOrDefault(srcId, String.valueOf(srcId))
                    : String.valueOf(srcId);

            File thumbsDir = new File(source.getModuleDir(), ThumbTask.THUMBS_FOLDER_NAME);
            updateStatus(STATUS.get().withCurrentSource(externalId).withCurrentItem(null));

            System.out.println("--- Source " + externalId + " (" + source.getTotalItems() + " items) ---");

            LeafReader leafReader = source.getLeafReader();
            IndexSearcher searcher = source.getSearcher();
            Bits liveDocs = leafReader.getLiveDocs();

            for (int luceneDoc = 0; luceneDoc < leafReader.maxDoc(); luceneDoc++) {
                ensureNotInterrupted();

                if (liveDocs != null && !liveDocs.get(luceneDoc)) {
                    continue;
                }

                Set<String> fields = new HashSet<String>();
                fields.add(BasicProps.EXT);
                fields.add(BasicProps.CONTENTTYPE);
                fields.add(BasicProps.HASH);
                fields.add(BasicProps.THUMB);
                fields.add(BasicProps.NAME);
                fields.add(ThumbTask.HAS_THUMB);

                Document doc = searcher.doc(luceneDoc, fields);
                String ext = doc.get(BasicProps.EXT);
                if (ext == null || ext.isEmpty()) {
                    continue;
                }
                ext = ext.toLowerCase();
                if (!requestedExtensions.contains(ext)) {
                    continue;
                }

                String contentType = doc.get(BasicProps.CONTENTTYPE);
                if (!isProcessableType(ext, contentType)) {
                    incrementSkipped();
                    continue;
                }

                // Fast content-type pre-filter for PDFs: skip files with known non-PDF content type
                if (PDF_EXTENSIONS.contains(ext) && contentType != null && !contentType.isEmpty()
                        && !contentType.toLowerCase().startsWith("application/pdf")) {
                    incrementSkipped();
                    incrementInvalidPdf();
                    continue;
                }

                incrementMatched();
                String hash = doc.get(BasicProps.HASH);
                String name = doc.get(BasicProps.NAME);
                updateStatus(STATUS.get().withCurrentItem(name));

                if (hash == null || hash.isEmpty()) {
                    incrementSkipped();
                    continue;
                }

                if (!force && thumbAlreadyExists(doc, thumbsDir, hash)) {
                    incrementSkipped();
                    continue;
                }

                try {
                    int itemId = source.getId(luceneDoc);
                    IItem item = source.getItemByID(itemId);
                    if (item == null) {
                        incrementSkipped();
                        continue;
                    }

                    // Fast size check for PDFs
                    if (PDF_EXTENSIONS.contains(ext)) {
                        Long itemLen = item.getLength();
                        if (itemLen != null && itemLen < MIN_PDF_SIZE) {
                            incrementSkipped();
                            incrementInvalidPdf();
                            continue;
                        }
                    }

                    byte[] thumbBytes = generateThumbnail(item, ext);
                    if (thumbBytes != null && thumbBytes.length > 0) {
                        saveThumbToDisk(thumbsDir, hash, thumbBytes);
                        incrementGenerated();
                    } else {
                        incrementSkipped();
                    }
                } catch (Exception e) {
                    LOGGER.warn("Error generating thumbnail for {} ({})", name, ext, e);
                    recordError(buildItemErrorMessage(name, e));
                }

                StatusSnapshot snapshot = STATUS.get();
                if (snapshot.getMatched() % 100 == 0) {
                    System.out.print("\r  matched=" + snapshot.getMatched()
                            + " generated=" + snapshot.getGenerated()
                            + " skipped=" + snapshot.getSkipped()
                            + " invalidPdf=" + snapshot.getInvalidPdf()
                            + " errors=" + snapshot.getErrors() + "   ");
                    System.out.flush();
                }
            }

            StatusSnapshot snapshot = STATUS.get();
            System.out.println("\r  " + GREEN + "Done" + RESET
                    + " matched=" + snapshot.getMatched()
                    + " generated=" + snapshot.getGenerated()
                    + " skipped=" + snapshot.getSkipped()
                    + " invalidPdf=" + snapshot.getInvalidPdf()
                    + " errors=" + snapshot.getErrors() + "         ");
        }

        StatusSnapshot snapshot = STATUS.get();
        System.out.println(BOLD + "\n=== Summary ===" + RESET);
        System.out.println("Matched:    " + snapshot.getMatched());
        System.out.println("Generated:  " + GREEN + snapshot.getGenerated() + RESET);
        System.out.println("Skipped:    " + snapshot.getSkipped());
        System.out.println("InvalidPdf: " + (snapshot.getInvalidPdf() > 0 ? YELLOW + String.valueOf(snapshot.getInvalidPdf()) + RESET : "0"));
        System.out.println("Errors:     " + (snapshot.getErrors() > 0 ? RED + String.valueOf(snapshot.getErrors()) + RESET : "0"));
        System.out.println("Elapsed:    " + formatDuration(snapshot.getElapsedMs()));
    }

    private boolean thumbAlreadyExists(Document doc, File thumbsDir, String hash) {
        BytesRef thumbRef = doc.getBinaryValue(BasicProps.THUMB);
        if (thumbRef != null && thumbRef.length > 0) {
            return true;
        }

        File thumbFile = Util.getFileFromHash(thumbsDir, hash, ThumbTask.THUMB_EXT);
        if (!thumbFile.exists() || thumbFile.length() == 0) {
            return false;
        }

        String hasThumb = doc.get(ThumbTask.HAS_THUMB);
        return hasThumb == null || Boolean.parseBoolean(hasThumb) || thumbFile.exists();
    }

    private byte[] generateThumbnail(IItem item, String ext) throws Exception {
        if (PDF_EXTENSIONS.contains(ext)) {
            return generatePdfThumbnail(item);
        }
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return generateImageThumbnail(item);
        }
        if (LIBREOFFICE_EXTENSIONS.contains(ext)) {
            return generateLibreOfficeThumbnail(item);
        }
        return null;
    }

    private boolean isProcessableType(String ext, String contentType) {
        if (!LIBREOFFICE_EXTENSIONS.contains(ext)) {
            return true;
        }
        if (contentType == null || contentType.trim().isEmpty()) {
            return true;
        }
        return isLibreOfficeContentType(contentType);
    }

    private boolean isLibreOfficeContentType(String contentType) {
        String m = contentType.toLowerCase();
        return m.startsWith("application/msword")
                || m.equals("application/rtf")
                || m.startsWith("application/vnd.ms-word")
                || m.startsWith("application/vnd.openxmlformats-officedocument")
                || m.startsWith("application/vnd.oasis.opendocument")
                || m.startsWith("application/vnd.sun.xml")
                || m.startsWith("application/vnd.stardivision")
                || m.equals("application/vnd.visio")
                || m.equals("application/x-mspublisher")
                || m.equals("application/postscript")
                || m.equals("image/x-pcx")
                || m.equals("image/vnd.dxf")
                || m.equals("image/cdr")
                || m.equals("application/coreldraw")
                || m.equals("application/x-vnd.corel.zcf.draw.document+zip")
                || m.startsWith("application/vnd.ms-powerpoint")
                || m.startsWith("application/vnd.openxmlformats-officedocument.presentationml")
                || m.startsWith("application/vnd.ms-excel")
                || m.startsWith("application/x-tika-msworks-spreadsheet")
                || m.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml")
                || m.startsWith("application/vnd.oasis.opendocument.spreadsheet");
    }

    private byte[] generatePdfThumbnail(IItem item) throws Exception {
        try {
            // Fast PDF magic header check: read first 5 bytes before expensive getTempFile()
            if (!hasPdfMagicHeader(item)) {
                incrementInvalidPdf();
                return null;
            }

            File tempFile = item.getTempFile();
            if (tempFile == null) {
                return null;
            }

            try (PDFToThumb pdfToThumb = new PDFToThumb()) {
                BufferedImage img = pdfToThumb.getPdfThumb(tempFile, THUMB_SIZE);
                if (img == null) {
                    return null;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
                ImageIO.write(img, "jpg", baos);
                return baos.toByteArray();
            }
        } finally {
            cleanupItem(item);
        }
    }

    /**
     * Quick check: scans the first 1024 bytes from the item's stream
     * looking for the PDF magic header (%PDF-). Per the PDF specification
     * and PDFBox's COSParser.parsePDFHeader(), the signature can appear
     * anywhere within the first 1024 bytes (e.g. after BOM, whitespace,
     * or other preamble). This is orders of magnitude cheaper than
     * creating a temp file + loading the full document with PDFBox.
     */
    private boolean hasPdfMagicHeader(IItem item) {
        // PDFBox scans up to 1024 bytes for the %PDF- header
        final int SCAN_LIMIT = 1024;
        try (BufferedInputStream bis = item.getBufferedInputStream()) {
            if (bis == null) {
                return false;
            }
            byte[] buf = new byte[SCAN_LIMIT];
            int totalRead = 0;
            while (totalRead < buf.length) {
                int r = bis.read(buf, totalRead, buf.length - totalRead);
                if (r == -1) {
                    break;
                }
                totalRead += r;
            }
            if (totalRead < PDF_MAGIC.length) {
                return false;
            }
            // Scan for %PDF- anywhere within the buffer
            int scanEnd = totalRead - PDF_MAGIC.length;
            for (int pos = 0; pos <= scanEnd; pos++) {
                if (buf[pos] == PDF_MAGIC[0]
                        && buf[pos + 1] == PDF_MAGIC[1]
                        && buf[pos + 2] == PDF_MAGIC[2]
                        && buf[pos + 3] == PDF_MAGIC[3]
                        && buf[pos + 4] == PDF_MAGIC[4]) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] generateImageThumbnail(IItem item) throws Exception {
        try (InputStream is = item.getBufferedInputStream()) {
            BufferedImage img = ImageIO.read(is);
            if (img == null) {
                return null;
            }

            if (img.getWidth() > THUMB_SIZE || img.getHeight() > THUMB_SIZE) {
                img = ImageUtil.resizeImage(img, THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_BGR);
            }

            Graphics2D g = img.createGraphics();
            g.setColor(Color.black);
            g.drawRect(0, 0, img.getWidth() - 1, img.getHeight() - 1);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
            ImageIO.write(img, "jpg", baos);
            return baos.toByteArray();
        } finally {
            cleanupItem(item);
        }
    }

    private byte[] generateLibreOfficeThumbnail(IItem item) throws Exception {
        if (!isLibreOfficeAvailable()) {
            return null;
        }

        File loOutDir = null;
        File outFile = null;
        Process convertProcess = null;
        Process loEnvCreateProcess = null;
        try {
            File inFile = item.getTempFile();
            if (inFile == null) {
                return null;
            }

            loOutDir = Files.createTempDirectory("webapi-doc-thumb").toFile();
            String loOutUri = toLibreOfficeFileUri(loOutDir);

            List<String> envCmd = new ArrayList<String>();
            envCmd.add(libreOfficePath + "/program/soffice.bin");
            envCmd.add("--headless");
            envCmd.add("--quickstart");
            envCmd.add("--norestore");
            envCmd.add("--nolockcheck");
            envCmd.add("-env:UserInstallation=" + loOutUri);

            ProcessBuilder envPb = new ProcessBuilder(envCmd.toArray(new String[0]));
            envPb.redirectErrorStream(true);
            loEnvCreateProcess = envPb.start();
            String envOutput = readProcessOutput(loEnvCreateProcess);
            loEnvCreateProcess.waitFor();
            if (loEnvCreateProcess.exitValue() != 0) {
                LOGGER.info("LibreOffice profile initialization exited with code {}: {}",
                    loEnvCreateProcess.exitValue(), summarize(envOutput));
            }
                finishProcess(loEnvCreateProcess);
                loEnvCreateProcess = null;

            setLOTemp(loOutDir, loOutUri);

            List<String> cmd = new ArrayList<String>();
            cmd.add(libreOfficePath + "/program/soffice.bin");
            cmd.add("--convert-to");
            cmd.add("png");
            cmd.add(inFile.getAbsolutePath());
            cmd.add("--headless");
            cmd.add("--quickstart");
            cmd.add("--norestore");
            cmd.add("--nolockcheck");
            cmd.add("-env:UserInstallation=" + loOutUri);
            cmd.add("--outdir");
            cmd.add(loOutDir.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd.toArray(new String[0]));
            pb.redirectErrorStream(true);
            convertProcess = pb.start();
            String processOutput = readProcessOutput(convertProcess);
            convertProcess.waitFor();

            if (convertProcess.exitValue() != 0) {
                throw new IOException("LibreOffice conversion failed (exit=" + convertProcess.exitValue() + "): "
                        + summarize(processOutput));
            }

            String name = inFile.getName();
            int pos = name.lastIndexOf('.');
            if (pos >= 0) {
                name = name.substring(0, pos);
            }
            outFile = new File(loOutDir, name + ".png");
            if (!outFile.exists()) {
                throw new IOException("LibreOffice did not produce PNG output: " + summarize(processOutput));
            }

            BufferedImage img = ImageIO.read(outFile);
            if (img == null) {
                return null;
            }
            if (img.getWidth() > THUMB_SIZE || img.getHeight() > THUMB_SIZE) {
                img = ImageUtil.resizeImage(img, THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_BGR);
            }

            Graphics2D g = img.createGraphics();
            g.setColor(Color.black);
            g.drawRect(0, 0, img.getWidth() - 1, img.getHeight() - 1);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
            ImageIO.write(img, "jpg", baos);
            return baos.toByteArray();

        } finally {
            finishProcess(loEnvCreateProcess);
            if (convertProcess != null && convertProcess.isAlive()) {
                convertProcess.destroyForcibly();
            }
            if (outFile != null && outFile.exists()) {
                outFile.delete();
            }
            if (loOutDir != null && loOutDir.exists()) {
                deleteDirectory(loOutDir);
            }
            cleanupItem(item);
        }
    }

    private static void finishProcess(Process process) {
        try {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (Throwable ignore) {
        }
    }

    private void cleanupItem(IItem item) {
        try {
            item.dispose();
        } catch (Exception ignore) {
        }
    }

    private void saveThumbToDisk(File thumbsDir, String hash, byte[] thumbBytes) throws IOException {
        File thumbFile = Util.getFileFromHash(thumbsDir, hash, ThumbTask.THUMB_EXT);
        if (!thumbFile.getParentFile().exists()) {
            thumbFile.getParentFile().mkdirs();
        }

        File tmp = File.createTempFile("thumb", ".tmp", thumbFile.getParentFile());
        try {
            Files.write(tmp.toPath(), thumbBytes);
            if (!tmp.renameTo(thumbFile)) {
                thumbFile.delete();
                if (!tmp.renameTo(thumbFile)) {
                    throw new IOException("Failed to rename temp thumb to " + thumbFile);
                }
            }
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static synchronized boolean isLibreOfficeAvailable() {
        if (!libreOfficePathResolved) {
            libreOfficePathResolved = true;
            try {
                URL url = URLUtil.getURL(ThumbnailProcessor.class);
                File jarDir = new File(url.toURI()).getParentFile();
                LibreOfficeFinder loFinder = new LibreOfficeFinder(jarDir);
                libreOfficePath = loFinder.getLOPath(true);
            } catch (Exception e) {
                libreOfficePath = null;
            }
        }
        return libreOfficePath != null && !libreOfficePath.trim().isEmpty();
    }

    private static String readProcessOutput(Process process) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
        }
        return baos.toString();
    }

    private static void setLOTemp(File loOutDir, String loOutUri) {
        try {
            File cfgIn = new File(loOutDir, "user/registrymodifications.xcu");
            File cfgOut = new File(loOutDir, "user/registrymodifications.tmp");
            if (!cfgIn.exists()) {
                return;
            }
            try (BufferedReader in = new BufferedReader(new FileReader(cfgIn));
                    BufferedWriter out = new BufferedWriter(new FileWriter(cfgOut))) {
                String line;
                int cnt = 0;
                while ((line = in.readLine()) != null) {
                    out.write(line);
                    out.newLine();
                    if (++cnt == 2) {
                        out.write("<item oor:path=\"/org.openoffice.Office.Common/Misc\"><prop oor:name=\"FirstRun\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Common/Misc\"><prop oor:name=\"UseLocking\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Common/Save/Document\"><prop oor:name=\"AutoSave\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Common/Save/Document\"><prop oor:name=\"LoadPrinter\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Common/Save/Document\"><prop oor:name=\"CreateBackup\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Impress/Filter/Import/VBA\"><prop oor:name=\"Load\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Writer/Filter/Import/VBA\"><prop oor:name=\"Load\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Calc/Filter/Import/VBA\"><prop oor:name=\"Load\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Common/Path/Current\"><prop oor:name=\"Temp\" oor:op=\"fuse\"><value xsi:nil=\"true\"/></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Common/Path/Info\"><prop oor:name=\"WorkPathChanged\" oor:op=\"fuse\"><value>false</value></prop></item>");
                        out.newLine();
                        out.write("<item oor:path=\"/org.openoffice.Office.Paths/Paths/org.openoffice.Office.Paths:NamedPath['Temp']\"><prop oor:name=\"WritePath\" oor:op=\"fuse\"><value>"
                            + loOutUri + "</value></prop></item>");
                        out.newLine();
                    }
                }
            }
            cfgIn.delete();
            cfgOut.renameTo(cfgIn);
        } catch (Exception e) {
            LOGGER.warn("Error setting LibreOffice temp directory", e);
        }
    }

    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    private static String toLibreOfficeFileUri(File dir) {
        String uri = dir.toURI().toASCIIString();
        if (System.getProperty("os.name").toLowerCase().contains("windows")
                && uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            return "file:///" + uri.substring("file:/".length());
        }
        return uri;
    }

    private static void ensureNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
    }

    private static void updateStatus(StatusSnapshot snapshot) {
        STATUS.set(snapshot.withUpdatedAt(System.currentTimeMillis()));
    }

    private static void incrementMatched() {
        StatusSnapshot s = STATUS.get();
        updateStatus(s.withCounters(s.getMatched() + 1, s.getGenerated(), s.getSkipped(), s.getErrors()));
    }

    private static void incrementGenerated() {
        StatusSnapshot s = STATUS.get();
        updateStatus(s.withCounters(s.getMatched(), s.getGenerated() + 1, s.getSkipped(), s.getErrors()));
    }

    private static void incrementSkipped() {
        StatusSnapshot s = STATUS.get();
        updateStatus(s.withCounters(s.getMatched(), s.getGenerated(), s.getSkipped() + 1, s.getErrors()));
    }

    private static void incrementErrors() {
        StatusSnapshot s = STATUS.get();
        updateStatus(s.withCounters(s.getMatched(), s.getGenerated(), s.getSkipped(), s.getErrors() + 1));
    }

    private static void incrementInvalidPdf() {
        StatusSnapshot s = STATUS.get();
        updateStatus(s.withInvalidPdf(s.getInvalidPdf() + 1));
    }

    private static void recordError(String errorMessage) {
        StatusSnapshot s = STATUS.get();
        updateStatus(s.withError(s.getMatched(), s.getGenerated(), s.getSkipped(), s.getErrors() + 1,
                summarize(errorMessage)));
    }

    private static String buildItemErrorMessage(String itemName, Exception e) {
        String base = e.getMessage();
        if (base == null || base.trim().isEmpty()) {
            base = e.getClass().getSimpleName();
        }
        return itemName + ": " + base;
    }

    private static String summarize(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
        if (normalized.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_ERROR_MESSAGE_LENGTH - 3) + "...";
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60000) {
            return String.format("%.1fs", ms / 1000.0);
        } else {
            long minutes = ms / 60000;
            long seconds = (ms % 60000) / 1000;
            return String.format("%dm%ds", minutes, seconds);
        }
    }

    public static final class StatusSnapshot {
        private final boolean running;
        private final boolean finished;
        private final boolean cancelled;
        private final String message;
        private final String currentSource;
        private final String currentItem;
        private final Set<String> extensions;
        private final boolean force;
        private final long startedAt;
        private final long updatedAt;
        private final int matched;
        private final int generated;
        private final int skipped;
        private final int errors;
        private final int invalidPdf;
        private final String lastError;

        private StatusSnapshot(boolean running, boolean finished, boolean cancelled, String message,
                String currentSource, String currentItem, Set<String> extensions, boolean force,
            long startedAt, long updatedAt, int matched, int generated, int skipped, int errors,
            int invalidPdf, String lastError) {
            this.running = running;
            this.finished = finished;
            this.cancelled = cancelled;
            this.message = message;
            this.currentSource = currentSource;
            this.currentItem = currentItem;
            this.extensions = extensions;
            this.force = force;
            this.startedAt = startedAt;
            this.updatedAt = updatedAt;
            this.matched = matched;
            this.generated = generated;
            this.skipped = skipped;
            this.errors = errors;
            this.invalidPdf = invalidPdf;
            this.lastError = lastError;
        }

        public static StatusSnapshot idle() {
            long now = System.currentTimeMillis();
            return new StatusSnapshot(false, false, false, "idle", null, null,
                    Collections.<String>emptySet(), false, 0L, now, 0, 0, 0, 0, 0, null);
        }

        public static StatusSnapshot running(Set<String> extensions, boolean force, long startedAt) {
            return new StatusSnapshot(true, false, false, "running", null, null,
                    Collections.unmodifiableSet(new LinkedHashSet<String>(extensions)), force,
                    startedAt, startedAt, 0, 0, 0, 0, 0, null);
        }

        public StatusSnapshot withCurrentSource(String currentSource) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors, invalidPdf, lastError);
        }

        public StatusSnapshot withCurrentItem(String currentItem) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors, invalidPdf, lastError);
        }

        public StatusSnapshot withCounters(int matched, int generated, int skipped, int errors) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors, invalidPdf, lastError);
        }

        public StatusSnapshot withError(int matched, int generated, int skipped, int errors, String lastError) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors, invalidPdf, lastError);
        }

        public StatusSnapshot withInvalidPdf(int invalidPdf) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors, invalidPdf, lastError);
        }

        public StatusSnapshot withUpdatedAt(long updatedAt) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors, invalidPdf, lastError);
        }

        public StatusSnapshot finish(boolean cancelled, String message) {
            return new StatusSnapshot(false, true, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, System.currentTimeMillis(), matched, generated, skipped, errors,
                    invalidPdf, lastError);
        }

        public StatusSnapshot fail(String message) {
            return new StatusSnapshot(false, true, false, "failed: " + message, currentSource, currentItem,
                    extensions, force, startedAt, System.currentTimeMillis(), matched, generated, skipped, errors,
                    invalidPdf, lastError);
        }

        public boolean isRunning() {
            return running;
        }

        public boolean isFinished() {
            return finished;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public String getMessage() {
            return message;
        }

        public String getCurrentSource() {
            return currentSource;
        }

        public String getCurrentItem() {
            return currentItem;
        }

        public Set<String> getExtensions() {
            return extensions;
        }

        public boolean isForce() {
            return force;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public int getMatched() {
            return matched;
        }

        public int getGenerated() {
            return generated;
        }

        public int getSkipped() {
            return skipped;
        }

        public int getErrors() {
            return errors;
        }

        public int getInvalidPdf() {
            return invalidPdf;
        }

        public String getLastError() {
            return lastError;
        }

        public long getElapsedMs() {
            if (startedAt <= 0) {
                return 0;
            }
            return System.currentTimeMillis() - startedAt;
        }

        public String summaryLine() {
            String state = running ? "running" : (finished ? "finished" : "idle");
            return "state=" + state
                    + ", source=" + (currentSource != null ? currentSource : "-")
                    + ", item=" + (currentItem != null ? currentItem : "-")
                    + ", matched=" + matched
                    + ", generated=" + generated
                    + ", skipped=" + skipped
                    + ", invalidPdf=" + invalidPdf
                    + ", errors=" + errors
                    + (lastError != null ? ", lastError=" + lastError : "")
                    + ", elapsed=" + formatDuration(getElapsedMs())
                    + ", updated=" + Instant.ofEpochMilli(updatedAt);
        }
    }
}