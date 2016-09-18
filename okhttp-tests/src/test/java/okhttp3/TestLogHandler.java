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
package okhttp3;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * A log handler that records which log messages were published so that a calling test can make
 * assertions about them.
 */
public final class TestLogHandler extends Handler {
  private final BlockingQueue<String> logs = new LinkedBlockingQueue<>();

  @Override public void publish(LogRecord logRecord) {
    if (getFormatter() == null) {
      logs.add(logRecord.getLevel() + ": " + logRecord.getMessage());
    } else {
      logs.add(getFormatter().format(logRecord));
    }
  }

  @Override public void flush() {
  }

  @Override public void close() {
  }

  public String take() throws InterruptedException {
    String message = logs.poll(10, TimeUnit.SECONDS);
    if (message == null) {
      throw new AssertionError("Timed out waiting for log message.");
    }
    return message;
  }
}
