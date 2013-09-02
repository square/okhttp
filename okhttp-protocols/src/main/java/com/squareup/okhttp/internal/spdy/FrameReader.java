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

package com.squareup.okhttp.internal.spdy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Reads transport frames for SPDY/3 or HTTP/2.0. */
public interface FrameReader extends Closeable {
  void readConnectionHeader() throws IOException;
  boolean nextFrame(Handler handler) throws IOException;

  public interface Handler {
    void data(boolean inFinished, int streamId, InputStream in, int length) throws IOException;
    /**
     * Create or update incoming headers, creating the corresponding streams
     * if necessary. Frames that trigger this are SPDY SYN_STREAM, HEADERS, and
     * SYN_REPLY, and HTTP/2.0 HEADERS and PUSH_PROMISE.
     *
     * @param inFinished true if the sender will not send further frames.
     * @param outFinished true if the receiver should not send further frames.
     * @param streamId the stream owning these headers.
     * @param associatedStreamId the stream that triggered the sender to create
     *     this stream.
     * @param priority or -1 for no priority. For SPDY, priorities range from 0
     *     (highest) thru 7 (lowest). For HTTP/2.0, priorities range from 0
     *     (highest) thru 2**31-1 (lowest).
     */
    void headers(boolean outFinished, boolean inFinished, int streamId, int associatedStreamId,
        int priority, List<String> nameValueBlock, HeadersMode headersMode);
    void rstStream(int streamId, ErrorCode errorCode);
    void settings(boolean clearPrevious, Settings settings);
    void noop();
    void ping(boolean reply, int payload1, int payload2);
    void goAway(int lastGoodStreamId, ErrorCode errorCode);
    void windowUpdate(int streamId, int deltaWindowSize, boolean endFlowControl);
    void priority(int streamId, int priority);
  }
}
