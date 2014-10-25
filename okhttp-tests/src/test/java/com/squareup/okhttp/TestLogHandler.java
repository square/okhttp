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
package com.squareup.okhttp;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * A log handler that records which log messages were published so that a calling test can make
 * assertions about them.
 */
public final class TestLogHandler extends Handler {
  private final List<String> logs = new ArrayList<>();

  @Override public synchronized void publish(LogRecord logRecord) {
    logs.add(logRecord.getLevel() + ": " + logRecord.getMessage());
    notifyAll();
  }

  @Override public void flush() {
  }

  @Override public void close() throws SecurityException {
  }

  public synchronized String take() throws InterruptedException {
    while (logs.isEmpty()) {
      wait();
    }
    return logs.remove(0);
  }
}
