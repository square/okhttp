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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A scriptable sink. Like Mockito, but worse and requiring less configuration. */
class MockSink implements Sink {
  private final List<String> log = new ArrayList<String>();
  private final Map<Integer, IOException> callThrows = new LinkedHashMap<Integer, IOException>();

  public void assertLog(String... messages) {
    assertEquals(Arrays.asList(messages), log);
  }

  public void assertLogContains(String message) {
    assertTrue(log.contains(message));
  }

  public void scheduleThrow(int call, IOException e) {
    callThrows.put(call, e);
  }

  private void throwIfScheduled() throws IOException {
    IOException exception = callThrows.get(log.size() - 1);
    if (exception != null) throw exception;
  }

  @Override public void write(OkBuffer source, long byteCount) throws IOException {
    log.add("write(" + source + ", " + byteCount + ")");
    source.skip(byteCount);
    throwIfScheduled();
  }

  @Override public void flush() throws IOException {
    log.add("flush()");
    throwIfScheduled();
  }

  @Override public Sink deadline(Deadline deadline) {
    log.add("deadline()");
    return this;
  }

  @Override public void close() throws IOException {
    log.add("close()");
    throwIfScheduled();
  }
}
