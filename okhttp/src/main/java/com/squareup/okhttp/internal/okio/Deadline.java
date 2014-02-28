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
import java.util.concurrent.TimeUnit;

/**
 * The time that a requested operation is due. If the deadline is reached before
 * the operation has completed, the operation should be aborted.
 */
public class Deadline {
  public static final Deadline NONE = new Deadline() {
    @Override public Deadline start(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override public boolean reached() {
      return false;
    }
  };

  private long deadlineNanos;

  public Deadline() {
  }

  public Deadline start(long timeout, TimeUnit unit) {
    deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
    return this;
  }

  public boolean reached() {
    return System.nanoTime() - deadlineNanos >= 0; // Subtract to avoid overflow!
  }

  public void throwIfReached() throws IOException {
    // TODO: a more catchable exception type?
    if (reached()) throw new IOException("Deadline reached");
  }
}
