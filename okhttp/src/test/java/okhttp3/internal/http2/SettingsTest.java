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
package okhttp3.internal.http2;

import org.junit.Test;

import static okhttp3.internal.http2.Settings.DEFAULT_INITIAL_WINDOW_SIZE;
import static okhttp3.internal.http2.Settings.MAX_CONCURRENT_STREAMS;
import static org.assertj.core.api.Assertions.assertThat;

public final class SettingsTest {
  @Test public void unsetField() {
    Settings settings = new Settings();
    assertThat(settings.isSet(MAX_CONCURRENT_STREAMS)).isFalse();
    assertThat(settings.getMaxConcurrentStreams()).isEqualTo(Integer.MAX_VALUE);
  }

  @Test public void setFields() {
    Settings settings = new Settings();

    settings.set(Settings.HEADER_TABLE_SIZE, 8096);
    assertThat(settings.getHeaderTableSize()).isEqualTo(8096);

    assertThat(settings.getEnablePush(true)).isTrue();
    settings.set(Settings.ENABLE_PUSH, 1);
    assertThat(settings.getEnablePush(false)).isTrue();
    settings.clear();

    assertThat(settings.getMaxConcurrentStreams()).isEqualTo(Integer.MAX_VALUE);
    settings.set(MAX_CONCURRENT_STREAMS, 75);
    assertThat(settings.getMaxConcurrentStreams()).isEqualTo(75);

    settings.clear();
    assertThat(settings.getMaxFrameSize(16384)).isEqualTo(16384);
    settings.set(Settings.MAX_FRAME_SIZE, 16777215);
    assertThat(settings.getMaxFrameSize(16384)).isEqualTo(16777215);

    assertThat(settings.getMaxHeaderListSize(-1)).isEqualTo(-1);
    settings.set(Settings.MAX_HEADER_LIST_SIZE, 16777215);
    assertThat(settings.getMaxHeaderListSize(-1)).isEqualTo(16777215);

    assertThat(settings.getInitialWindowSize()).isEqualTo(
        DEFAULT_INITIAL_WINDOW_SIZE);
    settings.set(Settings.INITIAL_WINDOW_SIZE, 108);
    assertThat(settings.getInitialWindowSize()).isEqualTo(108);
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
    assertThat(a.getHeaderTableSize()).isEqualTo(10000);
    assertThat(a.getMaxHeaderListSize(-1)).isEqualTo(40000);
    assertThat(a.getInitialWindowSize()).isEqualTo(50000);
    assertThat(a.getMaxConcurrentStreams()).isEqualTo(60000);
  }
}
