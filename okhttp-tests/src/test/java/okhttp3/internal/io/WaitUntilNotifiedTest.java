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
package okhttp3.internal.io;

import java.io.InterruptedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okio.Timeout;
import org.junit.After;
import org.junit.Test;

import static junit.framework.TestCase.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class WaitUntilNotifiedTest {
  final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(0);

  @After public void tearDown() throws Exception {
    executorService.shutdown();
  }

  @Test public synchronized void notified() throws Exception {
    Timeout timeout = new Timeout();
    timeout.timeout(5000, TimeUnit.MILLISECONDS);

    double start = now();
    executorService.schedule(new Runnable() {
      @Override public void run() {
        synchronized (WaitUntilNotifiedTest.this) {
          WaitUntilNotifiedTest.this.notify();
        }
      }
    }, 250, TimeUnit.MILLISECONDS);

    Pipe.waitUntilNotified(this, timeout);
    assertElapsed(250.0, start);
  }

  @Test public synchronized void timeout() throws Exception {
    Timeout timeout = new Timeout();
    timeout.timeout(250, TimeUnit.MILLISECONDS);
    double start = now();
    try {
      Pipe.waitUntilNotified(this, timeout);
      fail();
    } catch (InterruptedIOException expected) {
      assertEquals("timeout", expected.getMessage());
    }
    assertElapsed(250.0, start);
  }

  @Test public synchronized void deadline() throws Exception {
    Timeout timeout = new Timeout();
    timeout.deadline(250, TimeUnit.MILLISECONDS);
    double start = now();
    try {
      Pipe.waitUntilNotified(this, timeout);
      fail();
    } catch (InterruptedIOException expected) {
      assertEquals("timeout", expected.getMessage());
    }
    assertElapsed(250.0, start);
  }

  @Test public synchronized void deadlineBeforeTimeout() throws Exception {
    Timeout timeout = new Timeout();
    timeout.timeout(5000, TimeUnit.MILLISECONDS);
    timeout.deadline(250, TimeUnit.MILLISECONDS);
    double start = now();
    try {
      Pipe.waitUntilNotified(this, timeout);
      fail();
    } catch (InterruptedIOException expected) {
      assertEquals("timeout", expected.getMessage());
    }
    assertElapsed(250.0, start);
  }

  @Test public synchronized void timeoutBeforeDeadline() throws Exception {
    Timeout timeout = new Timeout();
    timeout.timeout(250, TimeUnit.MILLISECONDS);
    timeout.deadline(5000, TimeUnit.MILLISECONDS);
    double start = now();
    try {
      Pipe.waitUntilNotified(this, timeout);
      fail();
    } catch (InterruptedIOException expected) {
      assertEquals("timeout", expected.getMessage());
    }
    assertElapsed(250.0, start);
  }

  @Test public synchronized void deadlineAlreadyReached() throws Exception {
    Timeout timeout = new Timeout();
    timeout.deadlineNanoTime(System.nanoTime());
    double start = now();
    try {
      Pipe.waitUntilNotified(this, timeout);
      fail();
    } catch (InterruptedIOException expected) {
      assertEquals("timeout", expected.getMessage());
    }
    assertElapsed(0.0, start);
  }

  @Test public synchronized void threadInterrupted() throws Exception {
    Timeout timeout = new Timeout();
    double start = now();
    Thread.currentThread().interrupt();
    try {
      Pipe.waitUntilNotified(this, timeout);
      fail();
    } catch (InterruptedIOException expected) {
      assertEquals("interrupted", expected.getMessage());
      assertFalse(Thread.interrupted());
    }
    assertElapsed(0.0, start);
  }

  /** Returns the nanotime in milliseconds as a double for measuring timeouts. */
  private double now() {
    return System.nanoTime() / 1000000.0d;
  }

  /**
   * Fails the test unless the time from start until now is duration, accepting differences in
   * -50..+150 milliseconds.
   */
  private void assertElapsed(double duration, double start) {
    assertEquals(duration, now() - start + 50d, 100.0);
  }
}
