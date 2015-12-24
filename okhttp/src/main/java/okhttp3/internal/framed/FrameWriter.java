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
import okio.Buffer;

/** Writes transport frames for SPDY/3 or HTTP/2. */
public interface FrameWriter extends Closeable {
  /** HTTP/2 only. */
  void connectionPreface() throws IOException;

  /** Informs the peer that we've applied its latest settings. */
  void ackSettings(Settings peerSettings) throws IOException;

  /**
   * HTTP/2 only. Send a push promise header block.
   *
   * <p>A push promise contains all the headers that pertain to a server-initiated request, and a
   * {@code promisedStreamId} to which response frames will be delivered. Push promise frames are
   * sent as a part of the response to {@code streamId}. The {@code promisedStreamId} has a priority
   * of one greater than {@code streamId}.
   *
   * @param streamId client-initiated stream ID.  Must be an odd number.
   * @param promisedStreamId server-initiated stream ID.  Must be an even number.
   * @param requestHeaders minimally includes {@code :method}, {@code :scheme}, {@code :authority},
   * and (@code :path}.
   */
  void pushPromise(int streamId, int promisedStreamId, List<Header> requestHeaders)
      throws IOException;

  /** SPDY/3 only. */
  void flush() throws IOException;

  void synStream(boolean outFinished, boolean inFinished, int streamId, int associatedStreamId,
      List<Header> headerBlock) throws IOException;

  void synReply(boolean outFinished, int streamId, List<Header> headerBlock)
      throws IOException;

  void headers(int streamId, List<Header> headerBlock) throws IOException;

  void rstStream(int streamId, ErrorCode errorCode) throws IOException;

  /** The maximum size of bytes that may be sent in a single call to {@link #data}. */
  int maxDataLength();

  /**
   * {@code source.length} may be longer than the max length of the variant's data frame.
   * Implementations must send multiple frames as necessary.
   *
   * @param source the buffer to draw bytes from. May be null if byteCount is 0.
   * @param byteCount must be between 0 and the minimum of {code source.length} and {@link
   * #maxDataLength}.
   */
  void data(boolean outFinished, int streamId, Buffer source, int byteCount) throws IOException;

  /** Write okhttp's settings to the peer. */
  void settings(Settings okHttpSettings) throws IOException;

  /**
   * Send a connection-level ping to the peer.  {@code ack} indicates this is a reply.  Payload
   * parameters are different between SPDY/3 and HTTP/2.
   *
   * <p>In SPDY/3, only the first {@code payload1} parameter is sent.  If the sender is a client, it
   * is an unsigned odd number. Likewise, a server will send an even number.
   *
   * <p>In HTTP/2, both {@code payload1} and {@code payload2} parameters are sent.  The data is
   * opaque binary, and there are no rules on the content.
   */
  void ping(boolean ack, int payload1, int payload2) throws IOException;

  /**
   * Tell the peer to stop creating streams and that we last processed {@code lastGoodStreamId}, or
   * zero if no streams were processed.
   *
   * @param lastGoodStreamId the last stream ID processed, or zero if no streams were processed.
   * @param errorCode reason for closing the connection.
   * @param debugData only valid for HTTP/2; opaque debug data to send.
   */
  void goAway(int lastGoodStreamId, ErrorCode errorCode, byte[] debugData) throws IOException;

  /**
   * Inform peer that an additional {@code windowSizeIncrement} bytes can be sent on {@code
   * streamId}, or the connection if {@code streamId} is zero.
   */
  void windowUpdate(int streamId, long windowSizeIncrement) throws IOException;
}
