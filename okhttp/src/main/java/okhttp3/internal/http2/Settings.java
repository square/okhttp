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

import java.util.Arrays;

/**
 * Settings describe characteristics of the sending peer, which are used by the receiving peer.
 * Settings are {@link Http2Connection connection} scoped.
 */
public final class Settings {
  /**
   * From the HTTP/2 specs, the default initial window size for all streams is 64 KiB. (Chrome 25
   * uses 10 MiB).
   */
  static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;

  /** HTTP/2: Size in bytes of the table used to decode the sender's header blocks. */
  static final int HEADER_TABLE_SIZE = 1;
  /** HTTP/2: The peer must not send a PUSH_PROMISE frame when this is 0. */
  static final int ENABLE_PUSH = 2;
  /** Sender's maximum number of concurrent streams. */
  static final int MAX_CONCURRENT_STREAMS = 4;
  /** HTTP/2: Size in bytes of the largest frame payload the sender will accept. */
  static final int MAX_FRAME_SIZE = 5;
  /** HTTP/2: Advisory only. Size in bytes of the largest header list the sender will accept. */
  static final int MAX_HEADER_LIST_SIZE = 6;
  /** Window size in bytes. */
  static final int INITIAL_WINDOW_SIZE = 7;

  /** Total number of settings. */
  static final int COUNT = 10;

  /** Bitfield of which flags that values. */
  private int set;

  /** Flag values. */
  private final int[] values = new int[COUNT];

  void clear() {
    set = 0;
    Arrays.fill(values, 0);
  }

  Settings set(int id, int value) {
    if (id >= values.length) {
      return this; // Discard unknown settings.
    }

    int bit = 1 << id;
    set |= bit;
    values[id] = value;
    return this;
  }

  /** Returns true if a value has been assigned for the setting {@code id}. */
  boolean isSet(int id) {
    int bit = 1 << id;
    return (set & bit) != 0;
  }

  /** Returns the value for the setting {@code id}, or 0 if unset. */
  int get(int id) {
    return values[id];
  }

  /** Returns the number of settings that have values assigned. */
  int size() {
    return Integer.bitCount(set);
  }

  /** Returns -1 if unset. */
  int getHeaderTableSize() {
    int bit = 1 << HEADER_TABLE_SIZE;
    return (bit & set) != 0 ? values[HEADER_TABLE_SIZE] : -1;
  }

  // TODO: honor this setting.
  boolean getEnablePush(boolean defaultValue) {
    int bit = 1 << ENABLE_PUSH;
    return ((bit & set) != 0 ? values[ENABLE_PUSH] : defaultValue ? 1 : 0) == 1;
  }

  // TODO: honor this setting.
  int getMaxConcurrentStreams(int defaultValue) {
    int bit = 1 << MAX_CONCURRENT_STREAMS;
    return (bit & set) != 0 ? values[MAX_CONCURRENT_STREAMS] : defaultValue;
  }

  int getMaxFrameSize(int defaultValue) {
    int bit = 1 << MAX_FRAME_SIZE;
    return (bit & set) != 0 ? values[MAX_FRAME_SIZE] : defaultValue;
  }

  int getMaxHeaderListSize(int defaultValue) {
    int bit = 1 << MAX_HEADER_LIST_SIZE;
    return (bit & set) != 0 ? values[MAX_HEADER_LIST_SIZE] : defaultValue;
  }

  int getInitialWindowSize() {
    int bit = 1 << INITIAL_WINDOW_SIZE;
    return (bit & set) != 0 ? values[INITIAL_WINDOW_SIZE] : DEFAULT_INITIAL_WINDOW_SIZE;
  }

  /**
   * Writes {@code other} into this. If any setting is populated by this and {@code other}, the
   * value and flags from {@code other} will be kept.
   */
  void merge(Settings other) {
    for (int i = 0; i < COUNT; i++) {
      if (!other.isSet(i)) continue;
      set(i, other.get(i));
    }
  }
}
