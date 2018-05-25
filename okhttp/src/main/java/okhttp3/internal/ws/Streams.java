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
package okhttp3.internal.ws;

import java.io.Closeable;
import okio.BufferedSink;
import okio.BufferedSource;

public abstract class Streams implements Closeable {
  public final boolean client;
  public final BufferedSource source;
  public final BufferedSink sink;

  public Streams(boolean client, BufferedSource source, BufferedSink sink) {
    this.client = client;
    this.source = source;
    this.sink = sink;
  }
}
