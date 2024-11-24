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
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * A downloader and extraction tool for java-cef builds.
 * <p>
 * Downloads for <a href="https://github.com/CinemaMod/java-cef">CinemaMod java-cef</a> are provided by the CinemaMod Group unless changed
 * in the MCEFSettings properties file; see {@link MCEFSettings}.
 * Email ds58@mailbox.org for any questions or concerns regarding the file hosting.
 */
public class MCEFResourceManager {

    private static final String JAVA_CEF_DOWNLOAD_URL =
            "${host}/java-cef-builds/${java-cef-commit}/${platform}.tar.gz";
    private static final String JAVA_CEF_CHECKSUM_DOWNLOAD_URL =
            "${host}/java-cef-builds/${java-cef-commit}/${platform}.tar.gz.sha256";

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
                if (tarGzArchive.exists() && !tarGzArchive.delete()) {
                    throw new IOException("Failed to delete existing tar.gz archive");
                }

                // Download JCEF from file hosting
                MCEF.INSTANCE.getLogger().info("Downloading JCEF...");
                progressTracker.setTask("Downloading JCEF");
                downloadFile(getJavaCefDownloadUrl(), tarGzArchive, progressTracker);

                if (platformDirectory.exists() && !platformDirectory.delete()) {
                    throw new IOException("Failed to delete existing platform directory");
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
                if (tarGzArchive.exists() && !tarGzArchive.delete()) {
                    // Retry deletion on exit
                    tarGzArchive.deleteOnExit();
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
            boolean sameContent = FileUtils.contentEquals(checksumFile, tempChecksumFile);

            if (sameContent) {
                tempChecksumFile.delete();
                return true;
            }
        }

        tempChecksumFile.renameTo(checksumFile);
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

    private void downloadFile(String urlString, File outputFile, MCEFProgressTracker percentCompleteConsumer) throws IOException {
        try {
            MCEF.INSTANCE.getLogger().debug("Downloading '{}' to '{}'", urlString, outputFile.getCanonicalPath());
        } catch (IOException e) {
            throw new RuntimeException("Error getting canonical path for file", e);
        }

        try {
            URL url = new URL(urlString);
            HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();

            int responseCode = httpConn.getResponseCode();
            if(responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("HTTP error code: " + responseCode);
            }

            int fileSize = httpConn.getContentLength();
            if (fileSize <= 0) {
                throw new RuntimeException("Cannot read file size or file size is 0");
            }

            try (BufferedInputStream inputStream = new BufferedInputStream(httpConn.getInputStream());
                 FileOutputStream outputStream = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[2048];
                int bytesRead;
                int readBytes = 0;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    readBytes += bytesRead;
                    float percentComplete = (float) readBytes / fileSize;
                    percentCompleteConsumer.setProgress(percentComplete);
                }
            } catch (IOException e) {
                throw new IOException("Error writing to file from input stream", e);
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid URL format for " + urlString, e);
        } catch (IOException e) {
            throw new IOException("Error connecting to " + urlString, e);
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
