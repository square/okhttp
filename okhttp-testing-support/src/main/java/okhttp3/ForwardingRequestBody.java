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
import javax.annotation.Nullable;
import okio.BufferedSink;

public class ForwardingRequestBody extends RequestBody {
  private final RequestBody delegate;

  public ForwardingRequestBody(RequestBody delegate) {
    if (delegate == null) throw new IllegalArgumentException("delegate == null");
    this.delegate = delegate;
  }

  public final RequestBody delegate() {
    return delegate;
  }

  @Override public @Nullable MediaType contentType() {
    return delegate.contentType();
  }

  @Override public long contentLength() throws IOException {
    return delegate.contentLength();
  }

  @Override public void writeTo(BufferedSink sink) throws IOException {
    delegate.writeTo(sink);
  }

  @Override public boolean isDuplex() {
    return delegate.isDuplex();
  }

  @Override public String toString() {
    return getClass().getSimpleName() + "(" + delegate.toString() + ")";
  }
}
