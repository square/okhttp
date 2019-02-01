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
package okhttp3.internal.duplex;

import java.io.IOException;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Pipe;

/**
 * A duplex request body that provides early writes via a pipe.
 */
public final class PipeDuplexRequestBody extends RequestBody implements DuplexRequestBody {
  private final Pipe pipe;
  private final @Nullable MediaType contentType;

  public PipeDuplexRequestBody(@Nullable MediaType contentType, long pipeMaxBufferSize) {
    this.pipe = new Pipe(pipeMaxBufferSize);
    this.contentType = contentType;
  }

  public BufferedSink createSink() {
    return Okio.buffer(pipe.sink());
  }

  @Override public @Nullable MediaType contentType() {
    return contentType;
  }

  @Override public void writeTo(BufferedSink sink) throws IOException {
    pipe.fold(sink);
  }
}
