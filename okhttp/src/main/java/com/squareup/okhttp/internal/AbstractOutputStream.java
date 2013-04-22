/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.squareup.okhttp.internal;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An output stream for an HTTP request body.
 *
 * <p>Since a single socket's output stream may be used to write multiple HTTP
 * requests to the same server, subclasses should not close the socket stream.
 */
public abstract class AbstractOutputStream extends OutputStream {
  protected boolean closed;

  @Override public final void write(int data) throws IOException {
    write(new byte[] { (byte) data });
  }

  protected final void checkNotClosed() throws IOException {
    if (closed) {
      throw new IOException("stream closed");
    }
  }

  /** Returns true if this stream was closed locally. */
  public boolean isClosed() {
    return closed;
  }
}
