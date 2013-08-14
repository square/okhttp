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
import java.util.List;

/** Writes transport frames for SPDY/3 or HTTP/2.0. */
public interface SpdyWriter extends Closeable {
  void connectionHeader();
  void flush() throws IOException;
  void synStream(boolean outFinished, boolean inFinished, int streamId, int associatedStreamId,
      int priority, int slot, List<String> nameValueBlock) throws IOException;
  void synReply(boolean outFinished, int streamId, List<String> nameValueBlock) throws IOException;
  void headers(int flags, int streamId, List<String> nameValueBlock) throws IOException;
  void rstStream(int streamId, int statusCode) throws IOException;
  void data(boolean outFinished, int streamId, byte[] data) throws IOException;
  void data(boolean outFinished, int streamId, byte[] data, int offset, int byteCount)
      throws IOException;
  void settings(int flags, Settings settings) throws IOException;
  void noop() throws IOException;
  void ping(int flags, int id) throws IOException;
  void goAway(int flags, int lastGoodStreamId, int statusCode) throws IOException;
  void windowUpdate(int streamId, int deltaWindowSize) throws IOException;
}
