/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package net.ccbluex.liquidbounce.mcef;

import net.ccbluex.liquidbounce.mcef.progress.MCEFProgressTracker;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Buffer;
import okio.Okio;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;

import java.io.*;

/**
 * A downloader and extraction tool for java-cef builds.
 * <p>
 * Downloads for <a href="https://github.com/CCBlueX/java-cef">CCBlueX MCEF java-cef</a> are provided by CCBlueX unless changed
 * in the MCEFSettings properties file; see {@link MCEFSettings}.
 * Email support@liquidbounce.net for any questions or concerns regarding the file hosting.
 */
public class MCEFResourceManager {

    private static final String JAVA_CEF_DOWNLOAD_URL =
            "${host}/mcef-cef/${java-cef-commit}/${platform}";
    private static final String JAVA_CEF_CHECKSUM_DOWNLOAD_URL =
            "${host}/mcef-cef/${java-cef-commit}/${platform}/checksum";

    private final String host;
    private final String javaCefCommitHash;
    private final MCEFPlatform platform;
    public final MCEFProgressTracker progressTracker = new MCEFProgressTracker();

    private final File commitDirectory;
    private final File platformDirectory;

    private MCEFResourceManager(String host, String javaCefCommitHash, MCEFPlatform platform, File directory) {
        this.host = host;
        this.javaCefCommitHash = javaCefCommitHash;
        this.platform = platform;
        this.commitDirectory = new File(directory, javaCefCommitHash);
        this.platformDirectory = new File(commitDirectory, platform.getNormalizedName());
    }

    public File getPlatformDirectory() {
        return platformDirectory;
    }
    public File getCommitDirectory() {
        return commitDirectory;
    }

    static MCEFResourceManager newResourceManager() throws IOException {
        var javaCefCommit = MCEF.INSTANCE.getJavaCefCommit();
        MCEF.INSTANCE.getLogger().info("JCEF Commit: " + javaCefCommit);
        var settings = MCEF.INSTANCE.getSettings();

        return new MCEFResourceManager(settings.getDownloadMirror(), javaCefCommit,
                MCEFPlatform.getPlatform(), settings.getLibrariesDirectory());
    }

    public boolean requiresDownload() throws IOException {
        if (!commitDirectory.exists() && !commitDirectory.mkdirs()) {
            throw new IOException("Failed to create directory");
        }

        var checksumFile = new File(commitDirectory, platform.getNormalizedName() + ".tar.gz.sha256");

        // We always download the checksum for the java-cef build
        // We will compare this with <platform>.tar.gz.sha256
        // If the contents of the files differ (or it doesn't exist locally), we know we need to redownload JCEF
        boolean checksumMatches;
        try {
            checksumMatches = compareChecksum(checksumFile);
        } catch (IOException e) {
            MCEF.INSTANCE.getLogger().error("Failed to compare checksum", e);

            // Assume checksum matches if we can't compare
            checksumMatches = true;
        }
        var platformDirectoryExists = platformDirectory.exists();

        MCEF.INSTANCE.getLogger().info("Checksum matches: " + checksumMatches);
        MCEF.INSTANCE.getLogger().info("Platform directory exists: " + platformDirectoryExists);

        return !checksumMatches || !platformDirectoryExists;
    }

    public void downloadJcef() throws IOException {
        var retry = 0;

        while (true) {
            try {
                var tarGzArchive = new File(commitDirectory, platform.getNormalizedName() + ".tar.gz");
                if (tarGzArchive.exists()) {
                    try {
                        FileUtils.forceDelete(tarGzArchive);
                    } catch (Exception e) {
                        MCEF.INSTANCE.getLogger().warn("Failed to delete existing .tar.gz file", e);
                    }
                }

                // Download JCEF from file hosting
                MCEF.INSTANCE.getLogger().info("Downloading JCEF...");
                progressTracker.setTask("Downloading JCEF");
                downloadFile(getJavaCefDownloadUrl(), tarGzArchive, progressTracker);

                // Delete existing platform directory
                if (platformDirectory.exists()) {
                    MCEF.INSTANCE.getLogger().info("Deleting existing platform directory...");

                    // Delete existing platform directory - if this fails,
                    // we hope [extractTarGz] will overwrite the existing files instead.
                    FileUtils.deleteQuietly(platformDirectory);
                }

                // Compare checksum of .tar.gz file with remote checksum file
                progressTracker.setTask("Comparing Checksum");

                var checksumFile = new File(commitDirectory, platform.getNormalizedName() + ".tar.gz.sha256");
                if (!compareChecksum(checksumFile, tarGzArchive)) {
                    throw new IOException("Checksum mismatch");
                }

                progressTracker.setProgress(1.0f);
                progressTracker.done();

                // Extract JCEF from tar.gz
                MCEF.INSTANCE.getLogger().info("Extracting JCEF...");
                extractTarGz(tarGzArchive, commitDirectory, progressTracker);
                if (tarGzArchive.exists() && !FileUtils.deleteQuietly(tarGzArchive)) {
                    // Retry deletion on exit
                    try {
                        FileUtils.forceDeleteOnExit(tarGzArchive);
                    } catch (Exception ignored) { }
                }
                break;
            } catch (Exception e) {
                MCEF.INSTANCE.getLogger().error("Failed to download and extract JCEF", e);
                retry++;

                // Retry up to 3 times
                if (retry >= 3) {
                    throw e;
                }
            }
        }

        progressTracker.done();
    }

    public String getHost() {
        return host;
    }

    public String getJavaCefDownloadUrl() {
        return formatURL(JAVA_CEF_DOWNLOAD_URL);
    }

    public String getJavaCefChecksumDownloadUrl() {
        return formatURL(JAVA_CEF_CHECKSUM_DOWNLOAD_URL);
    }

    private String formatURL(String url) {
        return url
                .replace("${host}", host)
                .replace("${java-cef-commit}", javaCefCommitHash)
                .replace("${platform}", platform.getNormalizedName());
    }

    /**
     * @return true if the jcef build checksum file matches the remote checksum file (for the {@link MCEFResourceManager#javaCefCommitHash}),
     * false if the jcef build checksum file did not exist or did not match; this means we should redownload JCEF
     * @throws IOException
     */
    private boolean compareChecksum(File checksumFile) throws IOException {
        // Create temporary checksum file with the same name as the real checksum file and .temp appended
        var tempChecksumFile = new File(checksumFile.getCanonicalPath() + ".temp");

        progressTracker.setTask("Downloading Checksum");
        downloadFile(getJavaCefChecksumDownloadUrl(), tempChecksumFile, progressTracker);

        if (checksumFile.exists()) {
            boolean sameContent = FileUtils.readFileToString(checksumFile, "UTF-8").trim()
                    .equals(FileUtils.readFileToString(tempChecksumFile, "UTF-8").trim());

            if (sameContent) {
                FileUtils.deleteQuietly(tempChecksumFile);
                return true;
            }

            // Delete existing checksum file if it doesn't match the new checksum
            FileUtils.delete(checksumFile);
        }

        FileUtils.moveFile(tempChecksumFile, checksumFile);
        return false;
    }

    private boolean compareChecksum(File checksumFile, File archiveFile) {
        progressTracker.setTask("Comparing Checksum");

        if (!checksumFile.exists()) {
            throw new RuntimeException("Checksum file does not exist");
        }

        try {
            var checksum = FileUtils.readFileToString(checksumFile, "UTF-8").trim();
            var actualChecksum = DigestUtils.sha256Hex(new FileInputStream(archiveFile)).trim();

            return checksum.equals(actualChecksum);
        } catch (IOException e) {
            throw new RuntimeException("Error reading checksum file", e);
        }
    }

    private void downloadFile(String urlString, File outputFile, MCEFProgressTracker percentCompleteConsumer)
            throws IOException {
        var client = new OkHttpClient();
        var request = new Request.Builder()
                .url(urlString)
                .build();

        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response status: " + response.code());
            }

            var body = response.body();

            var contentLength = body.contentLength();
            try (var source = body.source();
                 var sink = Okio.buffer(Okio.sink(outputFile))) {

                var buffer = new Buffer();
                var totalBytesRead = 0L;
                long bytesRead;

                while ((bytesRead = source.read(buffer, 8192)) != -1) {
                    sink.write(buffer, bytesRead);
                    totalBytesRead += bytesRead;

                    if (contentLength > 0) {
                        var percentComplete = (float) totalBytesRead / contentLength;
                        percentCompleteConsumer.setProgress(percentComplete);
                    }
                }
            }
        }
    }

    private void extractTarGz(File tarGzFile, File outputDirectory, MCEFProgressTracker percentCompleteConsumer)
            throws IOException {
        percentCompleteConsumer.setTask("Extracting");
        outputDirectory.mkdirs();

        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(tarGzFile)))) {
            long totalBytesRead = 0;
            float fileSizeEstimate = tarGzFile.length() * 2.6158204f; // Initial estimate for progress

            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                if (!entry.isDirectory()) {
                    File outputFile = new File(outputDirectory, entry.getName());
                    outputFile.getParentFile().mkdirs();

                    try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        byte[] buffer = new byte[8192]; // Adjust buffer size for optimal I/O
                        int bytesRead;
                        while ((bytesRead = tarInput.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            totalBytesRead += bytesRead;
                            float percentComplete = (float) totalBytesRead / fileSizeEstimate;
                            percentCompleteConsumer.setProgress(percentComplete);
                        }
                    }
                }
            }
        } finally {
            percentCompleteConsumer.setProgress(1.0f); // Ensure completion regardless of exceptions
            percentCompleteConsumer.done();
        }
    }

}
