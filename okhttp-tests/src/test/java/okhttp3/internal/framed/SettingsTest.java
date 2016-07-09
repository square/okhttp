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
package okhttp3.internal.framed;

import org.junit.Test;

import static okhttp3.internal.framed.Settings.DEFAULT_INITIAL_WINDOW_SIZE;
import static okhttp3.internal.framed.Settings.MAX_CONCURRENT_STREAMS;
import static org.junit.Assert.assertEquals;

public final class SettingsTest {
  @Test public void unsetField() {
    Settings settings = new Settings();
    assertEquals(-3, settings.getMaxConcurrentStreams(-3));
  }

  @Test public void setFields() {
    Settings settings = new Settings();

    settings.set(Settings.HEADER_TABLE_SIZE, 8096);
    assertEquals(8096, settings.getHeaderTableSize());

    assertEquals(true, settings.getEnablePush(true));
    settings.set(Settings.ENABLE_PUSH, 1);
    assertEquals(true, settings.getEnablePush(false));
    settings.clear();

    assertEquals(-3, settings.getMaxConcurrentStreams(-3));
    settings.set(MAX_CONCURRENT_STREAMS, 75);
    assertEquals(75, settings.getMaxConcurrentStreams(-3));

    settings.clear();
    assertEquals(16384, settings.getMaxFrameSize(16384));
    settings.set(Settings.MAX_FRAME_SIZE, 16777215);
    assertEquals(16777215, settings.getMaxFrameSize(16384));

    assertEquals(-1, settings.getMaxHeaderListSize(-1));
    settings.set(Settings.MAX_HEADER_LIST_SIZE, 16777215);
    assertEquals(16777215, settings.getMaxHeaderListSize(-1));

    assertEquals(DEFAULT_INITIAL_WINDOW_SIZE,
        settings.getInitialWindowSize());
    settings.set(Settings.INITIAL_WINDOW_SIZE, 108);
    assertEquals(108, settings.getInitialWindowSize());
  }

  @Test public void merge() {
    Settings a = new Settings();
    a.set(Settings.HEADER_TABLE_SIZE, 10000);
    a.set(Settings.MAX_HEADER_LIST_SIZE, 20000);
    a.set(Settings.INITIAL_WINDOW_SIZE, 30000);

    Settings b = new Settings();
    b.set(Settings.MAX_HEADER_LIST_SIZE, 40000);
    b.set(Settings.INITIAL_WINDOW_SIZE, 50000);
    b.set(Settings.MAX_CONCURRENT_STREAMS, 60000);

    a.merge(b);
    assertEquals(10000, a.getHeaderTableSize());
    assertEquals(40000, a.getMaxHeaderListSize(-1));
    assertEquals(50000, a.getInitialWindowSize());
    assertEquals(60000, a.getMaxConcurrentStreams(-1));
  }
}
