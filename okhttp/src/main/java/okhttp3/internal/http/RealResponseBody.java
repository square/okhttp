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

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;

public final class RealResponseBody extends ResponseBody {
  private final Headers headers;
  private final BufferedSource source;

  public RealResponseBody(Headers headers, BufferedSource source) {
    this.headers = headers;
    this.source = source;
  }

  @Override public MediaType contentType() {
    String contentType = headers.get("Content-Type");
    return contentType != null ? MediaType.parse(contentType) : null;
  }

  @Override public long contentLength() {
    return HttpHeaders.contentLength(headers);
  }

  @Override public BufferedSource source() {
    return source;
  }
}
