/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3;

import java.io.IOException;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/** Rewrites the request body sent to the server to be all uppercase. */
public final class UppercaseRequestInterceptor implements Interceptor {
  @Override public Response intercept(Chain chain) throws IOException {
    return chain.proceed(uppercaseRequest(chain.request()));
  }

  /** Returns a request that transforms {@code request} to be all uppercase. */
  private Request uppercaseRequest(Request request) {
    RequestBody uppercaseBody = new ForwardingRequestBody(request.body()) {
      @Override public void writeTo(BufferedSink sink) throws IOException {
        delegate().writeTo(Okio.buffer(uppercaseSink(sink)));
      }
    };
    return request.newBuilder()
        .method(request.method(), uppercaseBody)
        .build();
  }

  private Sink uppercaseSink(Sink sink) {
    return new ForwardingSink(sink) {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        ByteString bytes = source.readByteString(byteCount);
        delegate().write(new Buffer().write(bytes.toAsciiUppercase()), byteCount);
      }
    };
  }
}
