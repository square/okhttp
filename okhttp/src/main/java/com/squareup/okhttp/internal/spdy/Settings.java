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

import java.util.Arrays;

/**
 * Settings describe characteristics of the sending peer, which are used by the receiving peer.
 * Settings are {@link com.squareup.okhttp.internal.spdy.SpdyConnection connection} scoped.
 */
public final class Settings {
  /**
   * From the SPDY/3 and HTTP/2 specs, the default initial window size for all
   * streams is 64 KiB. (Chrome 25 uses 10 MiB).
   */
  static final int DEFAULT_INITIAL_WINDOW_SIZE = 64 * 1024;

  /** Peer request to clear durable settings. */
  static final int FLAG_CLEAR_PREVIOUSLY_PERSISTED_SETTINGS = 0x1;

  /** Sent by servers only. The peer requests this setting persisted for future connections. */
  static final int PERSIST_VALUE = 0x1;
  /** Sent by clients only. The client is reminding the server of a persisted value. */
  static final int PERSISTED = 0x2;

  /** spdy/3: Sender's estimate of max incoming kbps. */
  static final int UPLOAD_BANDWIDTH = 1;
  /** HTTP/2: Size in bytes of the table used to decode the sender's header blocks. */
  static final int HEADER_TABLE_SIZE = 1;
  /** spdy/3: Sender's estimate of max outgoing kbps. */
  static final int DOWNLOAD_BANDWIDTH = 2;
  /** HTTP/2: An endpoint must not send a PUSH_PROMISE frame when this is 0. */
  static final int ENABLE_PUSH = 2;
  /** spdy/3: Sender's estimate of millis between sending a request and receiving a response. */
  static final int ROUND_TRIP_TIME = 3;
  /** Sender's maximum number of concurrent streams. */
  static final int MAX_CONCURRENT_STREAMS = 4;
  /** spdy/3: Current CWND in Packets. */
  static final int CURRENT_CWND = 5;
  /** spdy/3: Retransmission rate. Percentage */
  static final int DOWNLOAD_RETRANS_RATE = 6;
  /** Window size in bytes. */
  static final int INITIAL_WINDOW_SIZE = 7;
  /** spdy/3: Window size in bytes. */
  static final int CLIENT_CERTIFICATE_VECTOR_SIZE = 8;
  /** Flow control options. */
  static final int FLOW_CONTROL_OPTIONS = 10;

  /** Total number of settings. */
  static final int COUNT = 10;

  /** If set, flow control is disabled for streams directed to the sender of these settings. */
  static final int FLOW_CONTROL_OPTIONS_DISABLED = 0x1;

  /** Bitfield of which flags that values. */
  private int set;

  /** Bitfield of flags that have {@link #PERSIST_VALUE}. */
  private int persistValue;

  /** Bitfield of flags that have {@link #PERSISTED}. */
  private int persisted;

  /** Flag values. */
  private final int[] values = new int[COUNT];

  void clear() {
    set = persistValue = persisted = 0;
    Arrays.fill(values, 0);
  }

  Settings set(int id, int idFlags, int value) {
    if (id >= values.length) {
      return this; // Discard unknown settings.
    }

    int bit = 1 << id;
    set |= bit;
    if ((idFlags & PERSIST_VALUE) != 0) {
      persistValue |= bit;
    } else {
      persistValue &= ~bit;
    }
    if ((idFlags & PERSISTED) != 0) {
      persisted |= bit;
    } else {
      persisted &= ~bit;
    }

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

  /** Returns the flags for the setting {@code id}, or 0 if unset. */
  int flags(int id) {
    int result = 0;
    if (isPersisted(id)) result |= Settings.PERSISTED;
    if (persistValue(id)) result |= Settings.PERSIST_VALUE;
    return result;
  }

  /** Returns the number of settings that have values assigned. */
  int size() {
    return Integer.bitCount(set);
  }

  /** spdy/3 only. */
  int getUploadBandwidth(int defaultValue) {
    int bit = 1 << UPLOAD_BANDWIDTH;
    return (bit & set) != 0 ? values[UPLOAD_BANDWIDTH] : defaultValue;
  }

  /** HTTP/2 only. Returns -1 if unset. */
  int getHeaderTableSize() {
    int bit = 1 << HEADER_TABLE_SIZE;
    return (bit & set) != 0 ? values[HEADER_TABLE_SIZE] : -1;
  }

  /** spdy/3 only. */
  int getDownloadBandwidth(int defaultValue) {
    int bit = 1 << DOWNLOAD_BANDWIDTH;
    return (bit & set) != 0 ? values[DOWNLOAD_BANDWIDTH] : defaultValue;
  }

  /** HTTP/2 only. */
  // TODO: honor this setting in HTTP/2.
  boolean getEnablePush(boolean defaultValue) {
    int bit = 1 << ENABLE_PUSH;
    return ((bit & set) != 0 ? values[ENABLE_PUSH] : defaultValue ? 1 : 0) == 1;
  }

  /** spdy/3 only. */
  int getRoundTripTime(int defaultValue) {
    int bit = 1 << ROUND_TRIP_TIME;
    return (bit & set) != 0 ? values[ROUND_TRIP_TIME] : defaultValue;
  }

  // TODO: honor this setting in spdy/3 and HTTP/2.
  int getMaxConcurrentStreams(int defaultValue) {
    int bit = 1 << MAX_CONCURRENT_STREAMS;
    return (bit & set) != 0 ? values[MAX_CONCURRENT_STREAMS] : defaultValue;
  }

  /** spdy/3 only. */
  int getCurrentCwnd(int defaultValue) {
    int bit = 1 << CURRENT_CWND;
    return (bit & set) != 0 ? values[CURRENT_CWND] : defaultValue;
  }

  /** spdy/3 only. */
  int getDownloadRetransRate(int defaultValue) {
    int bit = 1 << DOWNLOAD_RETRANS_RATE;
    return (bit & set) != 0 ? values[DOWNLOAD_RETRANS_RATE] : defaultValue;
  }

  int getInitialWindowSize(int defaultValue) {
    int bit = 1 << INITIAL_WINDOW_SIZE;
    return (bit & set) != 0 ? values[INITIAL_WINDOW_SIZE] : defaultValue;
  }

  /** spdy/3 only. */
  int getClientCertificateVectorSize(int defaultValue) {
    int bit = 1 << CLIENT_CERTIFICATE_VECTOR_SIZE;
    return (bit & set) != 0 ? values[CLIENT_CERTIFICATE_VECTOR_SIZE] : defaultValue;
  }

  // TODO: honor this setting in spdy/3 and HTTP/2.
  boolean isFlowControlDisabled() {
    int bit = 1 << FLOW_CONTROL_OPTIONS;
    int value = (bit & set) != 0 ? values[FLOW_CONTROL_OPTIONS] : 0;
    return (value & FLOW_CONTROL_OPTIONS_DISABLED) != 0;
  }

  /**
   * Returns true if this user agent should use this setting in future spdy/3
   * connections to the same host.
   */
  boolean persistValue(int id) {
    int bit = 1 << id;
    return (persistValue & bit) != 0;
  }

  /** Returns true if this setting was persisted. */
  boolean isPersisted(int id) {
    int bit = 1 << id;
    return (persisted & bit) != 0;
  }

  /**
   * Writes {@code other} into this. If any setting is populated by this and
   * {@code other}, the value and flags from {@code other} will be kept.
   */
  void merge(Settings other) {
    for (int i = 0; i < COUNT; i++) {
      if (!other.isSet(i)) continue;
      set(i, other.flags(i), other.get(i));
    }
  }
}
