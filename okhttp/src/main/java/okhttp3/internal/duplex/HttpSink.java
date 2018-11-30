/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3.internal.duplex;

import java.io.Closeable;
import java.io.IOException;
import okhttp3.Headers;
import okio.BufferedSink;

/**
 * A writable request or response that interleaves headers and data. Used for duplex!
 *
 * Currently only implemented for HTTP/2.
 */
public interface HttpSink extends Closeable {
  BufferedSink sink();
  void headers(Headers headers) throws IOException;
}
