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

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.bytes.ByteString;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

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

  public static final List<Protocol> HTTP2_SPDY3_AND_HTTP =
      Util.immutableList(Arrays.asList(HTTP_2, SPDY_3, HTTP_11));
  public static final List<Protocol> SPDY3_AND_HTTP11 =
      Util.immutableList(Arrays.asList(SPDY_3, HTTP_11));
  public static final List<Protocol> HTTP2_AND_HTTP_11 =
      Util.immutableList(Arrays.asList(HTTP_2, HTTP_11));

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

  /**
   * Returns the protocol matching {@code input} or {@link #HTTP_11} is on
   * {@code null}. Throws an {@link IOException} when {@code input} doesn't
   * match the {@link #name} of a supported protocol.
   */
  public static Protocol find(ByteString input) throws IOException {
    if (input == null) return HTTP_11;
    for (Protocol protocol : values()) {
      if (protocol.name.equals(input)) return protocol;
    }
    throw new IOException("Unexpected protocol: " + input.utf8());
  }
}
