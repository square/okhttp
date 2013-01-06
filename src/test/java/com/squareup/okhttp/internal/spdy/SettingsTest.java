/*
 * Copyright (C) 2012 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal.spdy;

import static com.squareup.okhttp.internal.spdy.Settings.DOWNLOAD_BANDWIDTH;
import static com.squareup.okhttp.internal.spdy.Settings.DOWNLOAD_RETRANS_RATE;
import static com.squareup.okhttp.internal.spdy.Settings.MAX_CONCURRENT_STREAMS;
import static com.squareup.okhttp.internal.spdy.Settings.PERSISTED;
import static com.squareup.okhttp.internal.spdy.Settings.PERSIST_VALUE;
import static com.squareup.okhttp.internal.spdy.Settings.UPLOAD_BANDWIDTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class SettingsTest {
    @Test public void unsetField() {
        Settings settings = new Settings();
        assertEquals(-3, settings.getUploadBandwidth(-3));
    }

    @Test public void setFields() {
        Settings settings = new Settings();

        assertEquals(-3, settings.getUploadBandwidth(-3));
        settings.set(Settings.UPLOAD_BANDWIDTH, 0, 42);
        assertEquals(42, settings.getUploadBandwidth(-3));

        assertEquals(-3, settings.getDownloadBandwidth(-3));
        settings.set(Settings.DOWNLOAD_BANDWIDTH, 0, 53);
        assertEquals(53, settings.getDownloadBandwidth(-3));

        assertEquals(-3, settings.getRoundTripTime(-3));
        settings.set(Settings.ROUND_TRIP_TIME, 0, 64);
        assertEquals(64, settings.getRoundTripTime(-3));

        assertEquals(-3, settings.getMaxConcurrentStreams(-3));
        settings.set(Settings.MAX_CONCURRENT_STREAMS, 0, 75);
        assertEquals(75, settings.getMaxConcurrentStreams(-3));

        assertEquals(-3, settings.getCurrentCwnd(-3));
        settings.set(Settings.CURRENT_CWND, 0, 86);
        assertEquals(86, settings.getCurrentCwnd(-3));

        assertEquals(-3, settings.getDownloadRetransRate(-3));
        settings.set(Settings.DOWNLOAD_RETRANS_RATE, 0, 97);
        assertEquals(97, settings.getDownloadRetransRate(-3));

        assertEquals(-3, settings.getInitialWindowSize(-3));
        settings.set(Settings.INITIAL_WINDOW_SIZE, 0, 108);
        assertEquals(108, settings.getInitialWindowSize(-3));

        assertEquals(-3, settings.getClientCertificateVectorSize(-3));
        settings.set(Settings.CLIENT_CERTIFICATE_VECTOR_SIZE, 0, 117);
        assertEquals(117, settings.getClientCertificateVectorSize(-3));
    }

    @Test public void isPersisted() {
        Settings settings = new Settings();

        // Initially false.
        assertFalse(settings.isPersisted(Settings.ROUND_TRIP_TIME));

        // Set no flags.
        settings.set(Settings.ROUND_TRIP_TIME, 0, 0);
        assertFalse(settings.isPersisted(Settings.ROUND_TRIP_TIME));

        // Set the wrong flag.
        settings.set(Settings.ROUND_TRIP_TIME, PERSIST_VALUE, 0);
        assertFalse(settings.isPersisted(Settings.ROUND_TRIP_TIME));

        // Set the right flag.
        settings.set(Settings.ROUND_TRIP_TIME, PERSISTED, 0);
        assertTrue(settings.isPersisted(Settings.ROUND_TRIP_TIME));

        // Set multiple flags.
        settings.set(Settings.ROUND_TRIP_TIME, PERSIST_VALUE | PERSISTED, 0);
        assertTrue(settings.isPersisted(Settings.ROUND_TRIP_TIME));

        // Clear the flag.
        settings.set(Settings.ROUND_TRIP_TIME, PERSIST_VALUE, 0);
        assertFalse(settings.isPersisted(Settings.ROUND_TRIP_TIME));

        // Clear all flags.
        settings.set(Settings.ROUND_TRIP_TIME, 0, 0);
        assertFalse(settings.isPersisted(Settings.ROUND_TRIP_TIME));
    }

    @Test public void persistValue() {
        Settings settings = new Settings();

        // Initially false.
        assertFalse(settings.persistValue(Settings.ROUND_TRIP_TIME));

        // Set no flags.
        settings.set(Settings.ROUND_TRIP_TIME, 0, 0);
        assertFalse(settings.persistValue(Settings.ROUND_TRIP_TIME));

        // Set the wrong flag.
        settings.set(Settings.ROUND_TRIP_TIME, PERSISTED, 0);
        assertFalse(settings.persistValue(Settings.ROUND_TRIP_TIME));

        // Set the right flag.
        settings.set(Settings.ROUND_TRIP_TIME, PERSIST_VALUE, 0);
        assertTrue(settings.persistValue(Settings.ROUND_TRIP_TIME));

        // Set multiple flags.
        settings.set(Settings.ROUND_TRIP_TIME, PERSIST_VALUE | PERSISTED, 0);
        assertTrue(settings.persistValue(Settings.ROUND_TRIP_TIME));

        // Clear the flag.
        settings.set(Settings.ROUND_TRIP_TIME, PERSISTED, 0);
        assertFalse(settings.persistValue(Settings.ROUND_TRIP_TIME));

        // Clear all flags.
        settings.set(Settings.ROUND_TRIP_TIME, 0, 0);
        assertFalse(settings.persistValue(Settings.ROUND_TRIP_TIME));
    }

    @Test public void merge() {
        Settings a = new Settings();
        a.set(UPLOAD_BANDWIDTH, PERSIST_VALUE, 100);
        a.set(DOWNLOAD_BANDWIDTH, PERSIST_VALUE, 200);
        a.set(DOWNLOAD_RETRANS_RATE, 0, 300);

        Settings b = new Settings();
        b.set(DOWNLOAD_BANDWIDTH, 0, 400);
        b.set(DOWNLOAD_RETRANS_RATE, PERSIST_VALUE, 500);
        b.set(MAX_CONCURRENT_STREAMS, PERSIST_VALUE, 600);

        a.merge(b);
        assertEquals(100, a.getUploadBandwidth(-1));
        assertEquals(PERSIST_VALUE, a.flags(UPLOAD_BANDWIDTH));
        assertEquals(400, a.getDownloadBandwidth(-1));
        assertEquals(0, a.flags(DOWNLOAD_BANDWIDTH));
        assertEquals(500, a.getDownloadRetransRate(-1));
        assertEquals(PERSIST_VALUE, a.flags(DOWNLOAD_RETRANS_RATE));
        assertEquals(600, a.getMaxConcurrentStreams(-1));
        assertEquals(PERSIST_VALUE, a.flags(MAX_CONCURRENT_STREAMS));
    }
}
