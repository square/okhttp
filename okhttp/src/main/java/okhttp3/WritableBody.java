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

import okio.BufferedSink;
import okio.BufferedSource;

import java.io.IOException;

public abstract class WritableBody extends Body {
  @Override public final BufferedSource source() {
    throw new UnsupportedOperationException();
  }

  @Override
  public abstract void writeTo(BufferedSink sink) throws IOException;

  @Override public final void close() {
    // Nothing to do!
  }
}
