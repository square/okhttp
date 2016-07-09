/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3;

import java.net.Socket;

/**
 * The sockets and streams of an HTTP, HTTPS, or HTTPS+HTTP/2 connection. May be used for multiple
 * HTTP request/response exchanges. Connections may be direct to the origin server or via a proxy.
 *
 * <p>Typically instances of this class are created, connected and exercised automatically by the
 * HTTP client. Applications may use this class to monitor HTTP connections as members of a
 * {@linkplain ConnectionPool connection pool}.
 *
 * <p>Do not confuse this class with the misnamed {@code HttpURLConnection}, which isn't so much a
 * connection as a single request/response exchange.
 *
 * <h3>Modern TLS</h3>
 *
 * <p>There are tradeoffs when selecting which options to include when negotiating a secure
 * connection to a remote host. Newer TLS options are quite useful:
 *
 * <ul>
 *     <li>Server Name Indication (SNI) enables one IP address to negotiate secure connections for
 *         multiple domain names.
 *     <li>Application Layer Protocol Negotiation (ALPN) enables the HTTPS port (443) to be used to
 *         negotiate HTTP/2.
 * </ul>
 *
 * <p>Unfortunately, older HTTPS servers refuse to connect when such options are presented. Rather
 * than avoiding these options entirely, this class allows a connection to be attempted with modern
 * options and then retried without them should the attempt fail.
 *
 * <h3>Connection Reuse</h3>
 *
 * <p>Each connection can carry a varying number streams, depending on the underlying protocol being
 * used. HTTP/1.x connections can carry either zero or one streams. HTTP/2 connections can carry any
 * number of streams, dynamically configured with {@code SETTINGS_MAX_CONCURRENT_STREAMS}. A
 * connection currently carrying zero streams is an idle stream. We keep it alive because reusing an
 * existing connection is typically faster than establishing a new one.
 *
 * <p>When a single logical call requires multiple streams due to redirects or authorization
 * challenges, we prefer to use the same physical connection for all streams in the sequence. There
 * are potential performance and behavior consequences to this preference. To support this feature,
 * this class separates <i>allocations</i> from <i>streams</i>. An allocation is created by a call,
 * used for one or more streams, and then released. An allocated connection won't be stolen by other
 * calls while a redirect or authorization challenge is being handled.
 *
 * <p>When the maximum concurrent streams limit is reduced, some allocations will be rescinded.
 * Attempting to create new streams on these allocations will fail.
 *
 * <p>Note that an allocation may be released before its stream is completed. This is intended to
 * make bookkeeping easier for the caller: releasing the allocation as soon as the terminal stream
 * has been found. But only complete the stream once its data stream has been exhausted.
 */
public interface Connection {
  /** Returns the route used by this connection. */
  Route route();

  /**
   * Returns the socket that this connection is using. Returns an {@linkplain
   * javax.net.ssl.SSLSocket SSL socket} if this connection is HTTPS. If this is an HTTP/2
   * connection the socket may be shared by multiple concurrent calls.
   */
  Socket socket();

  /**
   * Returns the TLS handshake used to establish this connection, or null if the connection is not
   * HTTPS.
   */
  Handshake handshake();

  /**
   * Returns the protocol negotiated by this connection, or {@link Protocol#HTTP_1_1} if no protocol
   * has been negotiated. This method returns {@link Protocol#HTTP_1_1} even if the remote peer is
   * using {@link Protocol#HTTP_1_0}.
   */
  Protocol protocol();
}
