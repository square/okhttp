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
package com.squareup.okhttp.internal.okio;

import java.io.IOException;

public final class RealBufferedSourceReadUtf8LineTest extends ReadUtf8LineTest {
  /** Returns a buffered source that gets bytes of {@code data} one at a time. */
  @Override protected BufferedSource newSource(String s) {
    final OkBuffer buffer = new OkBuffer().writeUtf8(s);

    Source slowSource = new Source() {
      @Override public long read(OkBuffer sink, long byteCount) throws IOException {
        return buffer.read(sink, Math.min(1, byteCount));
      }

      @Override public Source deadline(Deadline deadline) {
        throw new UnsupportedOperationException();
      }

      @Override public void close() throws IOException {
        throw new UnsupportedOperationException();
      }
    };

    return Okio.buffer(slowSource);
  }
}
