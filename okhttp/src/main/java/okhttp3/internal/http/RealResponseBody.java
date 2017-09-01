/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.http;

import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;

public final class RealResponseBody extends ResponseBody {
  /**
   * Use a string to avoid parsing the content type until needed. This also defers problems caused
   * by malformed content types.
   */
  private final @Nullable String contentTypeString;
  private final long contentLength;
  private final BufferedSource source;

  public RealResponseBody(
      @Nullable String contentTypeString, long contentLength, BufferedSource source) {
    this.contentTypeString = contentTypeString;
    this.contentLength = contentLength;
    this.source = source;
  }

  @Override public MediaType contentType() {
    return contentTypeString != null ? MediaType.parse(contentTypeString) : null;
  }

  @Override public long contentLength() {
    return contentLength;
  }

  @Override public BufferedSource source() {
    return source;
  }
}
