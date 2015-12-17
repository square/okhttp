/*
 * Copyright (C) 2011 The Android Open Source Project
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

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import okio.BufferedSource;
import okio.ByteString;

/** Reads transport frames for SPDY/3 or HTTP/2. */
public interface FrameReader extends Closeable {
  void readConnectionPreface() throws IOException;
  boolean nextFrame(Handler handler) throws IOException;

  interface Handler {
    void data(boolean inFinished, int streamId, BufferedSource source, int length)
        throws IOException;

    /**
     * Create or update incoming headers, creating the corresponding streams
     * if necessary. Frames that trigger this are SPDY SYN_STREAM, HEADERS, and
     * SYN_REPLY, and HTTP/2 HEADERS and PUSH_PROMISE.
     *
     * @param outFinished true if the receiver should not send further frames.
     * @param inFinished true if the sender will not send further frames.
     * @param streamId the stream owning these headers.
     * @param associatedStreamId the stream that triggered the sender to create
     *     this stream.
     */
    void headers(boolean outFinished, boolean inFinished, int streamId, int associatedStreamId,
        List<Header> headerBlock, HeadersMode headersMode);
    void rstStream(int streamId, ErrorCode errorCode);
    void settings(boolean clearPrevious, Settings settings);

    /** HTTP/2 only. */
    void ackSettings();

    /**
     *  Read a connection-level ping from the peer.  {@code ack} indicates this
     *  is a reply.  Payload parameters are different between SPDY/3 and HTTP/2.
     *  <p>
     *  In SPDY/3, only the first {@code payload1} parameter is set.  If the
     *  reader is a client, it is an unsigned even number.  Likewise, a server
     *  will receive an odd number.
     *  <p>
     *  In HTTP/2, both {@code payload1} and {@code payload2} parameters are
     *  set. The data is opaque binary, and there are no rules on the content.
     */
    void ping(boolean ack, int payload1, int payload2);

    /**
     * The peer tells us to stop creating streams.  It is safe to replay
     * streams with {@code ID > lastGoodStreamId} on a new connection.  In-
     * flight streams with {@code ID <= lastGoodStreamId} can only be replayed
     * on a new connection if they are idempotent.
     *
     * @param lastGoodStreamId the last stream ID the peer processed before
     *     sending this message. If {@code lastGoodStreamId} is zero, the peer
     *     processed no frames.
     * @param errorCode reason for closing the connection.
     * @param debugData only valid for HTTP/2; opaque debug data to send.
     */
    void goAway(int lastGoodStreamId, ErrorCode errorCode, ByteString debugData);

    /**
     * Notifies that an additional {@code windowSizeIncrement} bytes can be
     * sent on {@code streamId}, or the connection if {@code streamId} is zero.
     */
    void windowUpdate(int streamId, long windowSizeIncrement);

    /**
     * Called when reading a headers or priority frame. This may be used to
     * change the stream's weight from the default (16) to a new value.
     *
     * @param streamId stream which has a priority change.
     * @param streamDependency the stream ID this stream is dependent on.
     * @param weight relative proportion of priority in [1..256].
     * @param exclusive inserts this stream ID as the sole child of
     *     {@code streamDependency}.
     */
    void priority(int streamId, int streamDependency, int weight, boolean exclusive);

    /**
     * HTTP/2 only. Receive a push promise header block.
     * <p>
     * A push promise contains all the headers that pertain to a server-initiated
     * request, and a {@code promisedStreamId} to which response frames will be
     * delivered. Push promise frames are sent as a part of the response to
     * {@code streamId}.
     *
     * @param streamId client-initiated stream ID.  Must be an odd number.
     * @param promisedStreamId server-initiated stream ID.  Must be an even
     * number.
     * @param requestHeaders minimally includes {@code :method}, {@code :scheme},
     * {@code :authority}, and (@code :path}.
     */
    void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders)
        throws IOException;

    /**
     * HTTP/2 only. Expresses that resources for the connection or a client-
     * initiated stream are available from a different network location or
     * protocol configuration.
     *
     * <p>See <a href="http://tools.ietf.org/html/draft-ietf-httpbis-alt-svc-01">alt-svc</a>
     *
     * @param streamId when a client-initiated stream ID (odd number), the
     *     origin of this alternate service is the origin of the stream. When
     *     zero, the origin is specified in the {@code origin} parameter.
     * @param origin when present, the
     *     <a href="http://tools.ietf.org/html/rfc6454">origin</a> is typically
     *     represented as a combination of scheme, host and port. When empty,
     *     the origin is that of the {@code streamId}.
     * @param protocol an ALPN protocol, such as {@code h2}.
     * @param host an IP address or hostname.
     * @param port the IP port associated with the service.
     * @param maxAge time in seconds that this alternative is considered fresh.
     */
    void alternateService(int streamId, String origin, ByteString protocol, String host, int port,
        long maxAge);
  }
}
