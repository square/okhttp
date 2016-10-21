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
import okio.BufferedSink;
import okio.BufferedSource;

class StaticBody extends Body {
  private final MediaType contentType;
  private final long contentLength;
  private final byte[] content;
  private Buffer source;

  StaticBody(MediaType contentType, long contentLength, byte[] content) {
    this.contentType = contentType;
    this.contentLength = contentLength;
    this.content = content;
  }

  @Override
  public MediaType contentType() {
    return contentType;
  }

  @Override
  public long contentLength() {
    return contentLength;
  }

  @Override
  public BufferedSource source() {
    if (source == null) source = new Buffer().write(content);
    return source;
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    sink.write(content);
  }
}
