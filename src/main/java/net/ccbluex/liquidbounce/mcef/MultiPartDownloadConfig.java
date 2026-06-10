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

package net.ccbluex.liquidbounce.mcef;

/**
 * Controls HTTP range-based multipart downloads.
 *
 * @param enabled enables multipart downloads when the server supports byte ranges
 * @param maxConcurrency maximum number of concurrent range requests
 * @param minPartSizeBytes minimum target size for each multipart range
 * @param maxPartRetries maximum retry attempts for an interrupted multipart range request
 * @param retryBackoffMillis delay before retrying an interrupted multipart range request
 */
public record MultiPartDownloadConfig(boolean enabled, int maxConcurrency, long minPartSizeBytes, int maxPartRetries,
                                      long retryBackoffMillis) {

    public static final long DEFAULT_MIN_PART_SIZE_BYTES = 8L * 1024L * 1024L;
    public static final int DEFAULT_MAX_CONCURRENCY = 8;
    public static final int DEFAULT_MAX_PART_RETRIES = 3;
    public static final long DEFAULT_RETRY_BACKOFF_MILLIS = 500L;
    public static final MultiPartDownloadConfig DEFAULT = new MultiPartDownloadConfig(
            true,
            DEFAULT_MAX_CONCURRENCY,
            DEFAULT_MIN_PART_SIZE_BYTES,
            DEFAULT_MAX_PART_RETRIES,
            DEFAULT_RETRY_BACKOFF_MILLIS
    );
    public static final MultiPartDownloadConfig DISABLED = new MultiPartDownloadConfig(
            false,
            DEFAULT_MAX_CONCURRENCY,
            DEFAULT_MIN_PART_SIZE_BYTES,
            DEFAULT_MAX_PART_RETRIES,
            DEFAULT_RETRY_BACKOFF_MILLIS
    );

    public MultiPartDownloadConfig(boolean enabled, int maxConcurrency, long minPartSizeBytes) {
        this(enabled, maxConcurrency, minPartSizeBytes, DEFAULT_MAX_PART_RETRIES, DEFAULT_RETRY_BACKOFF_MILLIS);
    }

    public MultiPartDownloadConfig {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1");
        }
        if (minPartSizeBytes < 1L) {
            throw new IllegalArgumentException("minPartSizeBytes must be at least 1");
        }
        if (maxPartRetries < 0) {
            throw new IllegalArgumentException("maxPartRetries must be non-negative");
        }
        if (retryBackoffMillis < 0L) {
            throw new IllegalArgumentException("retryBackoffMillis must be non-negative");
        }
    }

}
