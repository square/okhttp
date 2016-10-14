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
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import okhttp3.internal.Util;
import okio.BufferedSource;

final class BomAwareReader extends Reader {
  private final BufferedSource source;
  private final Charset charset;

  private boolean closed;
  private Reader delegate;

  BomAwareReader(BufferedSource source, Charset charset) {
    this.source = source;
    this.charset = charset;
  }

  @Override public int read(char[] cbuf, int off, int len) throws IOException {
    if (closed) throw new IOException("Stream closed");

    Reader delegate = this.delegate;
    if (delegate == null) {
      Charset charset = Util.bomAwareCharset(source, this.charset);
      delegate = this.delegate = new InputStreamReader(source.inputStream(), charset);
    }
    return delegate.read(cbuf, off, len);
  }

  @Override public void close() throws IOException {
    closed = true;
    if (delegate != null) {
      delegate.close();
    } else {
      source.close();
    }
  }
}
