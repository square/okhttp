/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.okio.ByteString;

/**
 * Contains protocols that OkHttp supports
 * <a href="http://tools.ietf.org/html/draft-agl-tls-nextprotoneg-04">NPN</a> or
 * <a href="http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg">ALPN</a> selection.
 *
 * <p>
 * <h3>Protocol vs Scheme</h3>
 * Despite its name, {@link java.net.URL#getProtocol()} returns the
 * {@link java.net.URI#getScheme() scheme} (http, https, etc.) of the URL, not
 * the protocol (http/1.1, spdy/3.1, etc.).  OkHttp uses the word protocol to
 * indicate how HTTP messages are framed.
 */
public enum Protocol {
  HTTP_2("HTTP-draft-09/2.0", true),
  SPDY_3("spdy/3.1", true),
  HTTP_11("http/1.1", false);

  /** Identifier string used in NPN or ALPN selection. */
  public final ByteString name;

  /**
   * When true the protocol is binary framed and derived from SPDY.
   *
   * @see com.squareup.okhttp.internal.spdy.Variant
   */
  public final boolean spdyVariant;

  Protocol(String name, boolean spdyVariant) {
    this.name = ByteString.encodeUtf8(name);
    this.spdyVariant = spdyVariant;
  }
}
