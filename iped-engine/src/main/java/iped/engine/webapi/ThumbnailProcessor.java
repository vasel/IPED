package iped.engine.webapi;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.time.Instant;
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

                    byte[] thumbBytes = generateThumbnail(item, ext);
                    if (thumbBytes != null && thumbBytes.length > 0) {
                        saveThumbToDisk(thumbsDir, hash, thumbBytes);
                        incrementGenerated();
                    } else {
                        incrementErrors();
                    }
                } catch (Exception e) {
                    incrementErrors();
                }

                StatusSnapshot snapshot = STATUS.get();
                if (snapshot.getMatched() % 100 == 0) {
                    System.out.print("\r  matched=" + snapshot.getMatched()
                            + " generated=" + snapshot.getGenerated()
                            + " skipped=" + snapshot.getSkipped()
                            + " errors=" + snapshot.getErrors() + "   ");
                    System.out.flush();
                }
            }

            StatusSnapshot snapshot = STATUS.get();
            System.out.println("\r  " + GREEN + "Done" + RESET
                    + " matched=" + snapshot.getMatched()
                    + " generated=" + snapshot.getGenerated()
                    + " skipped=" + snapshot.getSkipped()
                    + " errors=" + snapshot.getErrors() + "         ");
        }

        StatusSnapshot snapshot = STATUS.get();
        System.out.println(BOLD + "\n=== Summary ===" + RESET);
        System.out.println("Matched:   " + snapshot.getMatched());
        System.out.println("Generated: " + GREEN + snapshot.getGenerated() + RESET);
        System.out.println("Skipped:   " + snapshot.getSkipped());
        System.out.println("Errors:    " + (snapshot.getErrors() > 0 ? RED + String.valueOf(snapshot.getErrors()) + RESET : "0"));
        System.out.println("Elapsed:   " + formatDuration(snapshot.getElapsedMs()));
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

    private byte[] generatePdfThumbnail(IItem item) throws Exception {
        try {
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
        try {
            File inFile = item.getTempFile();
            if (inFile == null) {
                return null;
            }

            loOutDir = Files.createTempDirectory("webapi-doc-thumb").toFile();
            String loOutPath = loOutDir.getAbsolutePath().replace('\\', '/');
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                loOutPath = '/' + loOutPath;
            }

            List<String> cmd = new java.util.ArrayList<String>();
            cmd.add(libreOfficePath + "/program/soffice.bin");
            cmd.add("--convert-to");
            cmd.add("png");
            cmd.add(inFile.getAbsolutePath());
            cmd.add("--headless");
            cmd.add("--quickstart");
            cmd.add("--norestore");
            cmd.add("--nolockcheck");
            cmd.add("-env:UserInstallation=file://" + loOutPath);
            cmd.add("--outdir");
            cmd.add(loOutDir.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd.toArray(new String[0]));
            pb.redirectErrorStream(true);
            convertProcess = pb.start();
            ignoreProcessOutput(convertProcess);
            convertProcess.waitFor();

            String name = inFile.getName();
            int pos = name.lastIndexOf('.');
            if (pos >= 0) {
                name = name.substring(0, pos);
            }
            outFile = new File(loOutDir, name + ".png");
            if (!outFile.exists()) {
                return null;
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

    private static void ignoreProcessOutput(Process process) {
        Thread t = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                while (is.read(buffer) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                }
            } catch (IOException e) {
            }
        }, "ThumbnailProcessor-stdout");
        t.setDaemon(true);
        t.start();
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

        private StatusSnapshot(boolean running, boolean finished, boolean cancelled, String message,
                String currentSource, String currentItem, Set<String> extensions, boolean force,
                long startedAt, long updatedAt, int matched, int generated, int skipped, int errors) {
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
        }

        public static StatusSnapshot idle() {
            long now = System.currentTimeMillis();
            return new StatusSnapshot(false, false, false, "idle", null, null,
                    Collections.<String>emptySet(), false, 0L, now, 0, 0, 0, 0);
        }

        public static StatusSnapshot running(Set<String> extensions, boolean force, long startedAt) {
            return new StatusSnapshot(true, false, false, "running", null, null,
                    Collections.unmodifiableSet(new LinkedHashSet<String>(extensions)), force,
                    startedAt, startedAt, 0, 0, 0, 0);
        }

        public StatusSnapshot withCurrentSource(String currentSource) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors);
        }

        public StatusSnapshot withCurrentItem(String currentItem) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors);
        }

        public StatusSnapshot withCounters(int matched, int generated, int skipped, int errors) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors);
        }

        public StatusSnapshot withUpdatedAt(long updatedAt) {
            return new StatusSnapshot(running, finished, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, updatedAt, matched, generated, skipped, errors);
        }

        public StatusSnapshot finish(boolean cancelled, String message) {
            return new StatusSnapshot(false, true, cancelled, message, currentSource, currentItem,
                    extensions, force, startedAt, System.currentTimeMillis(), matched, generated, skipped, errors);
        }

        public StatusSnapshot fail(String message) {
            return new StatusSnapshot(false, true, false, "failed: " + message, currentSource, currentItem,
                    extensions, force, startedAt, System.currentTimeMillis(), matched, generated, skipped, errors);
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
                    + ", errors=" + errors
                    + ", elapsed=" + formatDuration(getElapsedMs())
                    + ", updated=" + Instant.ofEpochMilli(updatedAt);
        }
    }
}