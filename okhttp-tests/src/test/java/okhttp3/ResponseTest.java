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
package okhttp3;

import java.io.IOException;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class ResponseTest {
  @Test public void peekShorterThanResponse() throws Exception {
    Response response = newResponse(responseBody("abcdef"));
    Body peekedBody = response.body().peek(3);
    assertEquals("abc", peekedBody.string());
    assertEquals("abcdef", response.body().string());
  }

  @Test public void peekLongerThanResponse() throws Exception {
    Response response = newResponse(responseBody("abc"));
    Body peekedBody = response.body().peek(6);
    assertEquals("abc", peekedBody.string());
    assertEquals("abc", response.body().string());
  }

  @Test public void peekAfterReadingResponse() throws Exception {
    Response response = newResponse(responseBody("abc"));
    assertEquals("abc", response.body().string());

    try {
      response.body().peek(3);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void eachPeakIsIndependent() throws Exception {
    Response response = newResponse(responseBody("abcdef"));
    Body p1 = response.body().peek(4);
    Body p2 = response.body().peek(2);
    assertEquals("abcdef", response.body().string());
    assertEquals("abcd", p1.string());
    assertEquals("ab", p2.string());
  }

  /**
   * Returns a new response body that refuses to be read once it has been closed. This is true of
   * most {@link BufferedSource} instances, but not of {@link Buffer}.
   */
  private Body responseBody(String content) {
    final Buffer data = new Buffer().writeUtf8(content);

    Source source = new Source() {
      boolean closed;

      @Override public void close() throws IOException {
        closed = true;
      }

      @Override public long read(Buffer sink, long byteCount) throws IOException {
        if (closed) throw new IllegalStateException();
        return data.read(sink, byteCount);
      }

      @Override public Timeout timeout() {
        return Timeout.NONE;
      }
    };

    return Body.create(null, -1, Okio.buffer(source));
  }

  private Response newResponse(Body responseBody) {
    return new Response.Builder()
        .request(new Request.Builder()
            .url("https://example.com/")
            .build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .body(responseBody)
        .build();
  }
}
