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
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** Rewrites the response body returned from the server to be all uppercase. */
public final class UppercaseResponseInterceptor implements Interceptor {
  @Override public Response intercept(Chain chain) throws IOException {
    return uppercaseResponse(chain.proceed(chain.request()));
  }

  private Response uppercaseResponse(Response response) {
    ResponseBody uppercaseBody = new ForwardingResponseBody(response.body()) {
      @Override public BufferedSource source() {
        return Okio.buffer(uppercaseSource(delegate().source()));
      }
    };
    return response.newBuilder()
        .body(uppercaseBody)
        .build();
  }

  private ForwardingSource uppercaseSource(BufferedSource source) {
    return new ForwardingSource(source) {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        Buffer buffer = new Buffer();
        long read = delegate().read(buffer, byteCount);
        if (read != -1L) sink.write(buffer.readByteString().toAsciiUppercase());
        return read;
      }
    };
  }
}
