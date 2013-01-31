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

import java.io.IOException;

/** Listener to be notified when a connected peer creates a new stream. */
public interface IncomingStreamHandler {
  IncomingStreamHandler REFUSE_INCOMING_STREAMS = new IncomingStreamHandler() {
    @Override public void receive(SpdyStream stream) throws IOException {
      stream.close(SpdyStream.RST_REFUSED_STREAM);
    }
  };

  /**
   * Handle a new stream from this connection's peer. Implementations should
   * respond by either {@link SpdyStream#reply replying to the stream} or
   * {@link SpdyStream#close closing it}. This response does not need to be
   * synchronous.
   */
  void receive(SpdyStream stream) throws IOException;
}
