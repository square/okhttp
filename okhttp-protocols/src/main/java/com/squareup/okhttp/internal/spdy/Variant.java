/*
 * Copyright (C) 2013 Square, Inc.
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

import com.squareup.okhttp.Protocol;
import java.io.InputStream;
import java.io.OutputStream;

/** A version and dialect of the framed socket protocol. */
interface Variant {
  Variant SPDY3 = new Spdy3();
  Variant HTTP_20_DRAFT_09 = new Http20Draft09();

  /** The protocol as selected using NPN or ALPN. */
  Protocol getProtocol();

  /**
   * Default settings used for sending frames to the peer.
   * @param client true if these settings apply to writing requests, false if responses.
   */
  Settings defaultOkHttpSettings(boolean client);

  /**
   * Initial settings used for reading frames from the peer until we are sent
   * a Settings frame.
   * @param client true if these settings apply to reading responses, false if requests.
   */
  Settings initialPeerSettings(boolean client);

  /**
   * @param peerSettings potentially stale settings that reflect the remote peer.
   * @param client true if this is the HTTP client's reader, reading frames from a server.
   */
  FrameReader newReader(InputStream in, Settings peerSettings, boolean client);

  /**
   * @param okHttpSettings settings configured locally.
   * @param client true if this is the HTTP client's writer, writing frames to a server.
   */
  FrameWriter newWriter(OutputStream out, Settings okHttpSettings, boolean client);

}
