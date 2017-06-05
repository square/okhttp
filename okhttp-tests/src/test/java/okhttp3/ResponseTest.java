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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public final class ResponseTest {
  @Test public void peekShorterThanResponse() throws Exception {
    Response response = newResponse(responseBody("abcdef"));
    ResponseBody peekedBody = response.peekBody(3);
    assertEquals("abc", peekedBody.string());
    assertEquals("abcdef", response.body().string());
  }

  @Test public void peekLongerThanResponse() throws Exception {
    Response response = newResponse(responseBody("abc"));
    ResponseBody peekedBody = response.peekBody(6);
    assertEquals("abc", peekedBody.string());
    assertEquals("abc", response.body().string());
  }

  @Test public void peekAfterReadingResponse() throws Exception {
    Response response = newResponse(responseBody("abc"));
    assertEquals("abc", response.body().string());

    try {
      response.peekBody(3);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void eachPeakIsIndependent() throws Exception {
    Response response = newResponse(responseBody("abcdef"));
    ResponseBody p1 = response.peekBody(4);
    ResponseBody p2 = response.peekBody(2);
    assertEquals("abcdef", response.body().string());
    assertEquals("abcd", p1.string());
    assertEquals("ab", p2.string());
  }

  @Test public void closeDoesNotThrowNPEIfBodyIsNull() throws Exception {
    Response response = newResponse(null);
    response.close();
    assertNull(response.body());
  }

  @Test public void closingResponseTwiceSucceeds() throws Exception {
    Response response = newResponse(responseBody("abcdef"));
    assertNotNull(response.body());
    response.close();
    response.close();
  }

  /**
   * Returns a new response body that refuses to be read once it has been closed. This is true of
   * most {@link BufferedSource} instances, but not of {@link Buffer}.
   */
  private ResponseBody responseBody(String content) {
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

    return ResponseBody.create(null, -1, Okio.buffer(source));
  }

  private Response newResponse(ResponseBody responseBody) {
    return new Response.Builder()
        .request(new Request.Builder()
            .url("https://example.com/")
            .build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(responseBody)
        .build();
  }
}
