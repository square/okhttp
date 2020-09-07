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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A log handler that records which log messages were published so that a calling test can make
 * assertions about them.
 */
public final class TestLogHandler extends Handler implements TestRule {
  private final Logger logger;
  private final BlockingQueue<String> logs = new LinkedBlockingQueue<>();

  public TestLogHandler(Class<?> loggerName) {
    logger = Logger.getLogger(loggerName.getName());
  }

  @Override public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override public void evaluate() throws Throwable {
        Level previousLevel = logger.getLevel();
        logger.addHandler(TestLogHandler.this);
        logger.setLevel(Level.FINEST);
        try {
          base.evaluate();
        } finally {
          logger.setLevel(previousLevel);
          logger.removeHandler(TestLogHandler.this);
        }
      }
    };
  }

  @Override public void publish(LogRecord logRecord) {
    logs.add(logRecord.getLevel() + ": " + logRecord.getMessage());
  }

  @Override public void flush() {
  }

  @Override public void close() {
  }

  public List<String> takeAll() {
    List<String> list = new ArrayList<>();
    logs.drainTo(list);
    return list;
  }

  public String take() throws Exception {
    String message = logs.poll(10, TimeUnit.SECONDS);
    if (message == null) {
      throw new AssertionError("Timed out waiting for log message.");
    }
    return message;
  }
}
