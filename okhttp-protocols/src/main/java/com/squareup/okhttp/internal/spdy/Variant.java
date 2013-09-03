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

import java.io.InputStream;
import java.io.OutputStream;

/** A version and dialect of the framed socket protocol. */
interface Variant {
  Variant SPDY3 = new Spdy3();
  Variant HTTP_20_DRAFT_04 = new Http20Draft04();

  String getName();

  /**
   * @param client true if this is the HTTP client's reader, reading frames from
   *     a peer SPDY or HTTP/2 server.
   */
  FrameReader newReader(InputStream in, boolean client);

  /**
   * @param client true if this is the HTTP client's writer, writing frames to a
   *     peer SPDY or HTTP/2 server.
   */
  FrameWriter newWriter(OutputStream out, boolean client);
}
