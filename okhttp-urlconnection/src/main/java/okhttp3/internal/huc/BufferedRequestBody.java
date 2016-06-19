/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.huc;

import java.io.IOException;
import okhttp3.Request;
import okio.Buffer;
import okio.BufferedSink;

/**
 * This request body involves an application thread only. First all bytes are written to the buffer.
 * Only once that is complete are bytes then copied to the network.
 *
 * <p>This body has two special powers. First, it can retransmit the same request body multiple
 * times in order to recover from failures or cope with redirects. Second, it can compute the total
 * length of the request body by measuring it after it has been written to the output stream.
 */
final class BufferedRequestBody extends OutputStreamRequestBody {
  final Buffer buffer = new Buffer();
  long contentLength = -1L;

  BufferedRequestBody(long expectedContentLength) {
    initOutputStream(buffer, expectedContentLength);
  }

  @Override public long contentLength() throws IOException {
    return contentLength;
  }

  /**
   * Now that we've buffered the entire request body, update the request headers and the body
   * itself. This happens late to enable HttpURLConnection users to complete the socket connection
   * before sending request body bytes.
   */
  @Override public Request prepareToSendRequest(Request request) throws IOException {
    if (request.header("Content-Length") != null) return request;

    outputStream().close();
    contentLength = buffer.size();
    return request.newBuilder()
        .removeHeader("Transfer-Encoding")
        .header("Content-Length", Long.toString(buffer.size()))
        .build();
  }

  @Override public void writeTo(BufferedSink sink) throws IOException {
    buffer.copyTo(sink.buffer(), 0, buffer.size());
  }
}
