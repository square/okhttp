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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public final class ResponseBodyTest {
  @Test public void stringEmpty() throws IOException {
    ResponseBody body = body("");
    assertThat(body.string()).isEqualTo("");
  }

  @Test public void stringLooksLikeBomButTooShort() throws IOException {
    ResponseBody body = body("000048");
    assertThat(body.string()).isEqualTo("\0\0H");
  }

  @Test public void stringDefaultsToUtf8() throws IOException {
    ResponseBody body = body("68656c6c6f");
    assertThat(body.string()).isEqualTo("hello");
  }

  @Test public void stringExplicitCharset() throws IOException {
    ResponseBody body = body("00000068000000650000006c0000006c0000006f", "utf-32be");
    assertThat(body.string()).isEqualTo("hello");
  }

  @Test public void stringBomOverridesExplicitCharset() throws IOException {
    ResponseBody body = body("0000ffff00000068000000650000006c0000006c0000006f", "utf-8");
    assertThat(body.string()).isEqualTo("hello");
  }

  @Test public void stringBomUtf8() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    assertThat(body.string()).isEqualTo("hello");
  }

  @Test public void stringBomUtf16Be() throws IOException {
    ResponseBody body = body("feff00680065006c006c006f");
    assertThat(body.string()).isEqualTo("hello");
  }

  @Test public void stringBomUtf16Le() throws IOException {
    ResponseBody body = body("fffe680065006c006c006f00");
    assertThat(body.string()).isEqualTo("hello");
  }

  @Test public void stringBomUtf32Be() throws IOException {
    ResponseBody body = body("0000ffff00000068000000650000006c0000006c0000006f");
    assertThat(body.string()).isEqualTo("hello");
  }

  @Test public void stringBomUtf32Le() throws IOException {
    ResponseBody body = body("ffff000068000000650000006c0000006c0000006f000000");
    assertThat(body.string()).isEqualTo("hello");
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
    assertThat(body.string()).isEqualTo("hello");
    assertThat(closed.get()).isTrue();
  }

  @Test public void readerEmpty() throws IOException {
    ResponseBody body = body("");
    assertThat(exhaust(body.charStream())).isEqualTo("");
  }

  @Test public void readerLooksLikeBomButTooShort() throws IOException {
    ResponseBody body = body("000048");
    assertThat(exhaust(body.charStream())).isEqualTo("\0\0H");
  }

  @Test public void readerDefaultsToUtf8() throws IOException {
    ResponseBody body = body("68656c6c6f");
    assertThat(exhaust(body.charStream())).isEqualTo("hello");
  }

  @Test public void readerExplicitCharset() throws IOException {
    ResponseBody body = body("00000068000000650000006c0000006c0000006f", "utf-32be");
    assertThat(exhaust(body.charStream())).isEqualTo("hello");
  }

  @Test public void readerBomUtf8() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    assertThat(exhaust(body.charStream())).isEqualTo("hello");
  }

  @Test public void readerBomUtf16Be() throws IOException {
    ResponseBody body = body("feff00680065006c006c006f");
    assertThat(exhaust(body.charStream())).isEqualTo("hello");
  }

  @Test public void readerBomUtf16Le() throws IOException {
    ResponseBody body = body("fffe680065006c006c006f00");
    assertThat(exhaust(body.charStream())).isEqualTo("hello");
  }

  @Test public void readerBomUtf32Be() throws IOException {
    ResponseBody body = body("0000ffff00000068000000650000006c0000006c0000006f");
    assertThat(exhaust(body.charStream())).isEqualTo("hello");
  }

  @Test public void readerBomUtf32Le() throws IOException {
    ResponseBody body = body("ffff000068000000650000006c0000006c0000006f000000");
    assertThat(exhaust(body.charStream())).isEqualTo("hello");
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
    assertThat(closed.get()).isTrue();
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
    assertThat(reader.read()).isEqualTo('h');
    reader.close();
    assertThat(closed.get()).isTrue();
  }

  @Test public void sourceEmpty() throws IOException {
    ResponseBody body = body("");
    BufferedSource source = body.source();
    assertThat(source.exhausted()).isTrue();
    assertThat(source.readUtf8()).isEqualTo("");
  }

  @Test public void sourceSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    BufferedSource source = body.source();
    assertThat((source.readByte() & 0xff)).isEqualTo(0xef);
    assertThat((source.readByte() & 0xff)).isEqualTo(0xbb);
    assertThat((source.readByte() & 0xff)).isEqualTo(0xbf);
    assertThat(source.readUtf8()).isEqualTo("hello");
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
    assertThat(closed.get()).isTrue();
  }

  @Test public void bytesEmpty() throws IOException {
    ResponseBody body = body("");
    assertThat(body.bytes().length).isEqualTo(0);
  }

  @Test public void bytesSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    byte[] bytes = body.bytes();
    assertThat((bytes[0] & 0xff)).isEqualTo(0xef);
    assertThat((bytes[1] & 0xff)).isEqualTo(0xbb);
    assertThat((bytes[2] & 0xff)).isEqualTo(0xbf);
    assertThat(new String(bytes, 3, 5, UTF_8)).isEqualTo("hello");
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
    assertThat(body.bytes().length).isEqualTo(5);
    assertThat(closed.get()).isTrue();
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
      assertThat(e.getMessage()).isEqualTo(
          "Content-Length (10) and stream length (5) disagree");
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
      assertThat(e.getMessage()).isEqualTo(
          "Cannot buffer entire body for content length: 2147483648");
    }
  }

  @Test public void byteStringEmpty() throws IOException {
    ResponseBody body = body("");
    assertThat(body.byteString()).isEqualTo(ByteString.EMPTY);
  }

  @Test public void byteStringSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    ByteString actual = body.byteString();
    ByteString expected = ByteString.decodeHex("efbbbf68656c6c6f");
    assertThat(actual).isEqualTo(expected);
  }

  @Test public void byteStringClosesUnderlyingSource() throws IOException {
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
    assertThat(body.byteString().size()).isEqualTo(5);
    assertThat(closed.get()).isTrue();
  }

  @Test public void byteStringThrowsWhenLengthsDisagree() {
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
      body.byteString();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo(
          "Content-Length (10) and stream length (5) disagree");
    }
  }

  @Test public void byteStringThrowsMoreThanIntMaxValue() {
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
      body.byteString();
      fail();
    } catch (IOException e) {
      assertThat(e.getMessage()).isEqualTo(
          "Cannot buffer entire body for content length: 2147483648");
    }
  }

  @Test public void byteStreamEmpty() throws IOException {
    ResponseBody body = body("");
    InputStream bytes = body.byteStream();
    assertThat(bytes.read()).isEqualTo(-1);
  }

  @Test public void byteStreamSeesBom() throws IOException {
    ResponseBody body = body("efbbbf68656c6c6f");
    InputStream bytes = body.byteStream();
    assertThat(bytes.read()).isEqualTo(0xef);
    assertThat(bytes.read()).isEqualTo(0xbb);
    assertThat(bytes.read()).isEqualTo(0xbf);
    assertThat(exhaust(new InputStreamReader(bytes, UTF_8))).isEqualTo("hello");
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
    assertThat(closed.get()).isTrue();
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
    assertThat(body.source().readUtf8()).isEqualTo("hello");
    body.close();
  }

  static ResponseBody body(String hex) {
    return body(hex, null);
  }

  static ResponseBody body(String hex, String charset) {
    MediaType mediaType = charset == null ? null : MediaType.get("any/thing; charset=" + charset);
    return ResponseBody.create(ByteString.decodeHex(hex), mediaType);
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
