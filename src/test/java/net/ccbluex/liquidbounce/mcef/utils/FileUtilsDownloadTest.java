/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package net.ccbluex.liquidbounce.mcef.utils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.ccbluex.liquidbounce.mcef.MultiPartDownloadConfig;
import net.ccbluex.liquidbounce.mcef.listeners.MCEFProgressListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FileUtilsDownloadTest {

    private static final int MULTI_PART_THRESHOLD = 32 * 1024 * 1024;

    @TempDir
    private Path tempDirectory;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void downloadFileUsesMultiPartDownloadWhenRangeIsSupported() throws Exception {
        var data = createData(MULTI_PART_THRESHOLD + 1024);
        var handler = new DownloadHandler(data, true);
        server = createServer(handler);

        var outputFile = tempDirectory.resolve("range.bin").toFile();
        var listener = new RecordingProgressListener(data.length);

        FileUtils.downloadFile(listener, "range", serverUrl(), outputFile);

        assertArrayEquals(data, Files.readAllBytes(outputFile.toPath()));
        assertEquals(1, handler.headRequests.get());
        assertEquals(5, handler.partialRequests.get());
        assertEquals(0, handler.wholeRequests.get());
        assertEquals(1, listener.starts.get());
        assertEquals(1, listener.ends.get());
        assertEquals(data.length, listener.doneBytes.get());
        assertEquals(data.length, listener.doneContentLength.get());
        assertTrue(listener.progressUpdates.get() > 0);
    }

    @Test
    void downloadFileUsesMultiPartDownloadWhenHeadRequestFails() throws Exception {
        var data = createData(MULTI_PART_THRESHOLD + 1024);
        var handler = new DownloadHandler(data, true, false, true);
        server = createServer(handler);

        var outputFile = tempDirectory.resolve("head-fails.bin").toFile();
        var listener = new RecordingProgressListener(data.length);

        FileUtils.downloadFile(listener, "head-fails", serverUrl(), outputFile);

        assertArrayEquals(data, Files.readAllBytes(outputFile.toPath()));
        assertEquals(1, handler.headRequests.get());
        assertEquals(5, handler.partialRequests.get());
        assertEquals(0, handler.wholeRequests.get());
        assertEquals(1, listener.starts.get());
        assertEquals(1, listener.ends.get());
        assertEquals(data.length, listener.doneBytes.get());
        assertEquals(data.length, listener.doneContentLength.get());
    }

    @Test
    void downloadFileFallsBackToSingleRequestForSmallFiles() throws Exception {
        var data = createData(4096);
        var handler = new DownloadHandler(data, true);
        server = createServer(handler);

        var outputFile = tempDirectory.resolve("small.bin").toFile();
        var listener = new RecordingProgressListener(data.length);

        FileUtils.downloadFile(listener, "small", serverUrl(), outputFile);

        assertArrayEquals(data, Files.readAllBytes(outputFile.toPath()));
        assertEquals(1, handler.headRequests.get());
        assertEquals(0, handler.partialRequests.get());
        assertEquals(1, handler.wholeRequests.get());
        assertEquals(1, listener.starts.get());
        assertEquals(1, listener.ends.get());
        assertEquals(data.length, listener.doneBytes.get());
        assertEquals(data.length, listener.doneContentLength.get());
    }

    @Test
    void downloadFileUsesConfiguredMultiPartLimits() throws Exception {
        var config = new MultiPartDownloadConfig(true, 3, 1024L * 1024L);

        var data = createData(8 * 1024 * 1024);
        var handler = new DownloadHandler(data, true);
        server = createServer(handler);

        var outputFile = tempDirectory.resolve("configured.bin").toFile();
        var listener = new RecordingProgressListener(data.length);

        FileUtils.downloadFile(listener, "configured", serverUrl(), outputFile, config);

        assertArrayEquals(data, Files.readAllBytes(outputFile.toPath()));
        assertEquals(1, handler.headRequests.get());
        assertEquals(4, handler.partialRequests.get());
        assertEquals(0, handler.wholeRequests.get());
    }

    @Test
    void downloadFileRetriesInterruptedPartRange() throws Exception {
        var config = new MultiPartDownloadConfig(true, 3, 1024L * 1024L, 2, 0L);

        var data = createData(8 * 1024 * 1024);
        var handler = new DownloadHandler(data, true, false, false, 1, 256 * 1024);
        server = createServer(handler);

        var outputFile = tempDirectory.resolve("interrupted-range.bin").toFile();
        var listener = new RecordingProgressListener(data.length);

        FileUtils.downloadFile(listener, "interrupted-range", serverUrl(), outputFile, config);

        assertArrayEquals(data, Files.readAllBytes(outputFile.toPath()));
        assertEquals(1, handler.headRequests.get());
        assertEquals(5, handler.partialRequests.get());
        assertEquals(0, handler.wholeRequests.get());
        assertEquals(1, handler.transientPartialFailures.get());
        assertEquals(data.length, listener.doneBytes.get());
        assertEquals(data.length, listener.doneContentLength.get());
    }

    @Test
    void downloadFileFailsWhenInterruptedPartRangeExceedsRetryLimit() throws Exception {
        var config = new MultiPartDownloadConfig(true, 3, 1024L * 1024L, 1, 0L);

        var data = createData(8 * 1024 * 1024);
        var handler = new DownloadHandler(data, true, false, false, 2, 256 * 1024);
        server = createServer(handler);

        var outputFile = tempDirectory.resolve("interrupted-range-fails.bin").toFile();
        var listener = new RecordingProgressListener(data.length);

        assertThrows(IOException.class, () ->
                FileUtils.downloadFile(listener, "interrupted-range-fails", serverUrl(), outputFile, config));

        assertFalse(outputFile.exists());
        assertEquals(1, handler.headRequests.get());
        assertTrue(handler.partialRequests.get() >= 5);
        assertEquals(0, handler.wholeRequests.get());
        assertEquals(2, handler.transientPartialFailures.get());
    }

    @Test
    void downloadFileFallsBackWhenMultiPartIsDisabledByConfig() throws Exception {
        var config = new MultiPartDownloadConfig(false, 8, 1024L * 1024L);

        var data = createData(8 * 1024 * 1024);
        var handler = new DownloadHandler(data, true);
        server = createServer(handler);

        var outputFile = tempDirectory.resolve("disabled.bin").toFile();
        var listener = new RecordingProgressListener(data.length);

        FileUtils.downloadFile(listener, "disabled", serverUrl(), outputFile, config);

        assertArrayEquals(data, Files.readAllBytes(outputFile.toPath()));
        assertEquals(0, handler.headRequests.get());
        assertEquals(0, handler.partialRequests.get());
        assertEquals(1, handler.wholeRequests.get());
    }

    @Test
    void downloadFileRejectsPartWithMismatchedContentRange() throws Exception {
        var data = createData(MULTI_PART_THRESHOLD + 1024);
        var handler = new DownloadHandler(data, true, true);
        server = createServer(handler);

        var outputFile = tempDirectory.resolve("mismatched-range.bin").toFile();
        var listener = new RecordingProgressListener(data.length);

        assertThrows(IOException.class, () -> FileUtils.downloadFile(listener, "mismatched-range", serverUrl(), outputFile));

        assertFalse(outputFile.exists());
        assertEquals(1, handler.headRequests.get());
        assertTrue(handler.partialRequests.get() > 1);
        assertEquals(0, handler.wholeRequests.get());
    }

    private HttpServer createServer(DownloadHandler handler) throws IOException {
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/file", handler);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
        return httpServer;
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
    }

    private static byte[] createData(int length) {
        var data = new byte[length];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) (index * 31 + 7);
        }
        return data;
    }

    private static final class DownloadHandler implements HttpHandler {
        private final byte[] data;
        private final boolean rangeSupported;
        private final boolean mismatchedPartContentRange;
        private final boolean failHeadRequest;
        private final int transientPartialFailureLimit;
        private final int transientPartialFailureBytes;
        private final AtomicInteger headRequests = new AtomicInteger();
        private final AtomicInteger partialRequests = new AtomicInteger();
        private final AtomicInteger wholeRequests = new AtomicInteger();
        private final AtomicInteger transientPartialFailures = new AtomicInteger();
        private final AtomicInteger transientFailurePartEnd = new AtomicInteger(-1);

        private DownloadHandler(byte[] data, boolean rangeSupported) {
            this(data, rangeSupported, false, false);
        }

        private DownloadHandler(byte[] data, boolean rangeSupported, boolean mismatchedPartContentRange) {
            this(data, rangeSupported, mismatchedPartContentRange, false);
        }

        private DownloadHandler(byte[] data, boolean rangeSupported, boolean mismatchedPartContentRange, boolean failHeadRequest) {
            this(data, rangeSupported, mismatchedPartContentRange, failHeadRequest, 0, 0);
        }

        private DownloadHandler(byte[] data, boolean rangeSupported, boolean mismatchedPartContentRange, boolean failHeadRequest,
                                int transientPartialFailureLimit, int transientPartialFailureBytes) {
            this.data = data;
            this.rangeSupported = rangeSupported;
            this.mismatchedPartContentRange = mismatchedPartContentRange;
            this.failHeadRequest = failHeadRequest;
            this.transientPartialFailureLimit = transientPartialFailureLimit;
            this.transientPartialFailureBytes = transientPartialFailureBytes;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Accept-Ranges", rangeSupported ? "bytes" : "none");
            exchange.getResponseHeaders().add("Content-Length", Integer.toString(data.length));

            if ("HEAD".equals(exchange.getRequestMethod())) {
                headRequests.incrementAndGet();
                exchange.sendResponseHeaders(failHeadRequest ? 403 : 200, -1);
                exchange.close();
                return;
            }

            var range = exchange.getRequestHeaders().getFirst("Range");
            if (rangeSupported && range != null && range.startsWith("bytes=")) {
                respondPartial(exchange, range);
            } else {
                respondWhole(exchange);
            }
        }

        private void respondPartial(HttpExchange exchange, String range) throws IOException {
            partialRequests.incrementAndGet();

            var bounds = range.substring("bytes=".length()).split("-", 2);
            var start = Integer.parseInt(bounds[0]);
            var end = bounds[1].isEmpty() ? data.length - 1 : Integer.parseInt(bounds[1]);
            var length = end - start + 1;
            var responseStart = start;
            var responseEnd = end;
            if (mismatchedPartContentRange && !(start == 0 && end == 0)) {
                responseStart++;
                responseEnd++;
            }

            exchange.getResponseHeaders().set("Content-Length", Integer.toString(length));
            exchange.getResponseHeaders().add("Content-Range", "bytes " + responseStart + "-" + responseEnd + "/" + data.length);
            exchange.sendResponseHeaders(206, length);

            if (shouldFailTransiently(end)) {
                exchange.getResponseBody().write(data, start, Math.min(length, transientPartialFailureBytes));
                exchange.close();
                return;
            }

            exchange.getResponseBody().write(data, start, length);
            exchange.close();
        }

        private boolean shouldFailTransiently(int end) {
            if (transientPartialFailureLimit <= 0 || end == 0) {
                return false;
            }

            while (true) {
                var selectedPartEnd = transientFailurePartEnd.get();
                if (selectedPartEnd == -1) {
                    if (!transientFailurePartEnd.compareAndSet(-1, end)) {
                        continue;
                    }
                    selectedPartEnd = end;
                }
                if (selectedPartEnd != end) {
                    return false;
                }

                var failures = transientPartialFailures.get();
                if (failures >= transientPartialFailureLimit) {
                    return false;
                }
                if (transientPartialFailures.compareAndSet(failures, failures + 1)) {
                    return true;
                }
            }
        }

        private void respondWhole(HttpExchange exchange) throws IOException {
            wholeRequests.incrementAndGet();

            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        }
    }

    private static final class RecordingProgressListener implements MCEFProgressListener {
        private final long expectedContentLength;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger ends = new AtomicInteger();
        private final AtomicInteger progressUpdates = new AtomicInteger();
        private final AtomicLong doneBytes = new AtomicLong();
        private final AtomicLong doneContentLength = new AtomicLong();

        private RecordingProgressListener(long expectedContentLength) {
            this.expectedContentLength = expectedContentLength;
        }

        @Override
        public void onProgressUpdate(String task, float progress) {
            assertTrue(progress >= 0.0f && progress <= 1.0f, "Progress must be in [0, 1]");
            progressUpdates.incrementAndGet();
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onFileStart(String task) {
            starts.incrementAndGet();
        }

        @Override
        public void onFileProgress(String task, long bytesRead, long contentLength, boolean done) {
            assertEquals(expectedContentLength, contentLength);

            if (done) {
                doneBytes.set(bytesRead);
                doneContentLength.set(contentLength);
            }
        }

        @Override
        public void onFileEnd(String task) {
            ends.incrementAndGet();
        }
    }
}
