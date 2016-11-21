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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicBoolean;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ResponseBodyTest {
  @Test public void stringEmpty() throws IOException {
    ResponseBody body = body("");
    assertEquals("", body.string());
  }

  @Test public void stringLooksLikeBomButTooShort() throws IOException {
    ResponseBody body = body("000048");
    assertEquals("\0\0H", body.string());
  }

  @Test public void stringDefaultsToUtf8() throws IOException {
    ResponseBody body = body("68656c6c6f");
    assertEquals("hello", body.string());
  }

  @Test public void stringExplicitCharset() throws IOException {
    ResponseBody body = body("00000068000000650000006c0000006c0000006f", "utf-32be");
    assertEquals("hello", body.string());
  }

  @Test public void stringBomOverridesExplicitCharset() throws IOException {
    ResponseBody body = body("0000ffff00000068000000650000006c0000006c0000006f", "utf-8");
    assertEquals("hello", body.string());
  }

  @Test public void stringBomUtf8() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    assertEquals("hello", body.string());
  }

  @Test public void stringBomUtf16Be() throws IOException {
    ResponseBody body = body("feff00680065006c006c006f");
    assertEquals("hello", body.string());
  }

  @Test public void stringBomUtf16Le() throws IOException {
    ResponseBody body = body("fffe680065006c006c006f00");
    assertEquals("hello", body.string());
  }

  @Test public void stringBomUtf32Be() throws IOException {
    ResponseBody body = body("0000ffff00000068000000650000006c0000006c0000006f");
    assertEquals("hello", body.string());
  }

  @Test public void stringBomUtf32Le() throws IOException {
    ResponseBody body = body("ffff000068000000650000006c0000006c0000006f000000");
    assertEquals("hello", body.string());
  }

  @Test public void stringClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 5;
      }

      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return Okio.buffer(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    assertEquals("hello", body.string());
    assertTrue(closed.get());
  }

  @Test public void readerEmpty() throws IOException {
    ResponseBody body = body("");
    assertEquals("", exhaust(body.charStream()));
  }

  @Test public void readerLooksLikeBomButTooShort() throws IOException {
    ResponseBody body = body("000048");
    assertEquals("\0\0H", exhaust(body.charStream()));
  }

  @Test public void readerDefaultsToUtf8() throws IOException {
    ResponseBody body = body("68656c6c6f");
    assertEquals("hello", exhaust(body.charStream()));
  }

  @Test public void readerExplicitCharset() throws IOException {
    ResponseBody body = body("00000068000000650000006c0000006c0000006f", "utf-32be");
    assertEquals("hello", exhaust(body.charStream()));
  }

  @Test public void readerBomUtf8() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    assertEquals("hello", exhaust(body.charStream()));
  }

  @Test public void readerBomUtf16Be() throws IOException {
    ResponseBody body = body("feff00680065006c006c006f");
    assertEquals("hello", exhaust(body.charStream()));
  }

  @Test public void readerBomUtf16Le() throws IOException {
    ResponseBody body = body("fffe680065006c006c006f00");
    assertEquals("hello", exhaust(body.charStream()));
  }

  @Test public void readerBomUtf32Be() throws IOException {
    ResponseBody body = body("0000ffff00000068000000650000006c0000006c0000006f");
    assertEquals("hello", exhaust(body.charStream()));
  }

  @Test public void readerBomUtf32Le() throws IOException {
    ResponseBody body = body("ffff000068000000650000006c0000006c0000006f000000");
    assertEquals("hello", exhaust(body.charStream()));
  }

  @Test public void readerClosedBeforeBomClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 5;
      }

      @Override public BufferedSource source() {
        ResponseBody body = body("fffe680065006c006c006f00");
        return Okio.buffer(new ForwardingSource(body.source()) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    body.charStream().close();
    assertTrue(closed.get());
  }

  @Test public void readerClosedAfterBomClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 5;
      }

      @Override public BufferedSource source() {
        ResponseBody body = body("fffe680065006c006c006f00");
        return Okio.buffer(new ForwardingSource(body.source()) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    Reader reader = body.charStream();
    assertEquals('h', reader.read());
    reader.close();
    assertTrue(closed.get());
  }

  @Test public void sourceEmpty() throws IOException {
    ResponseBody body = body("");
    BufferedSource source = body.source();
    assertTrue(source.exhausted());
    assertEquals("", source.readUtf8());
  }

  @Test public void sourceSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    BufferedSource source = body.source();
    assertEquals(0xef, source.readByte() & 0xff);
    assertEquals(0xbb, source.readByte() & 0xff);
    assertEquals(0xbf, source.readByte() & 0xff);
    assertEquals("hello", source.readUtf8());
  }

  @Test public void sourceClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 5;
      }

      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return Okio.buffer(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    body.source().close();
    assertTrue(closed.get());
  }

  @Test public void bytesEmpty() throws IOException {
    ResponseBody body = body("");
    assertEquals(0, body.bytes().length);
  }

  @Test public void bytesSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    byte[] bytes = body.bytes();
    assertEquals(0xef, bytes[0] & 0xff);
    assertEquals(0xbb, bytes[1] & 0xff);
    assertEquals(0xbf, bytes[2] & 0xff);
    assertEquals("hello", new String(bytes, 3, 5, "UTF-8"));
  }

  @Test public void bytesClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 5;
      }

      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return Okio.buffer(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    assertEquals(5, body.bytes().length);
    assertTrue(closed.get());
  }

  @Test public void bytesThrowsWhenLengthsDisagree() {
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 10;
      }

      @Override public BufferedSource source() {
        return new Buffer().writeUtf8("hello");
      }
    };
    try {
      body.bytes();
      fail();
    } catch (IOException e) {
      assertEquals("Content-Length (10) and stream length (5) disagree", e.getMessage());
    }
  }

  @Test public void bytesThrowsMoreThanIntMaxValue() {
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return Integer.MAX_VALUE + 1L;
      }

      @Override public BufferedSource source() {
        throw new AssertionError();
      }
    };
    try {
      body.bytes();
      fail();
    } catch (IOException e) {
      assertEquals("Cannot buffer entire body for content length: 2147483648", e.getMessage());
    }
  }

  @Test public void byteStreamEmpty() throws IOException {
    ResponseBody body = body("");
    InputStream bytes = body.byteStream();
    assertEquals(-1, bytes.read());
  }

  @Test public void byteStreamSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    InputStream bytes = body.byteStream();
    assertEquals(0xef, bytes.read());
    assertEquals(0xbb, bytes.read());
    assertEquals(0xbf, bytes.read());
    assertEquals("hello", exhaust(new InputStreamReader(bytes, "utf-8")));
  }

  @Test public void byteStreamClosesUnderlyingSource() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 5;
      }

      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return Okio.buffer(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            closed.set(true);
            super.close();
          }
        });
      }
    };
    body.byteStream().close();
    assertTrue(closed.get());
  }

  @Test public void throwingUnderlyingSourceClosesQuietly() throws IOException {
    ResponseBody body = new ResponseBody() {
      @Override public MediaType contentType() {
        return null;
      }

      @Override public long contentLength() {
        return 5;
      }

      @Override public BufferedSource source() {
        Buffer source = new Buffer().writeUtf8("hello");
        return Okio.buffer(new ForwardingSource(source) {
          @Override public void close() throws IOException {
            throw new IOException("Broken!");
          }
        });
      }
    };
    assertEquals("hello", body.source().readUtf8());
    body.close();
  }

  static ResponseBody body(String hex) {
    return body(hex, null);
  }

  static ResponseBody body(String hex, String charset) {
    MediaType mediaType = charset == null ? null : MediaType.parse("any/thing; charset=" + charset);
    return ResponseBody.create(mediaType, ByteString.decodeHex(hex).toByteArray());
  }

  static String exhaust(Reader reader) throws IOException {
    StringBuilder builder = new StringBuilder();
    char[] buf = new char[10];
    int read;
    while ((read = reader.read(buf)) != -1) {
      builder.append(buf, 0, read);
    }
    return builder.toString();
  }
}
