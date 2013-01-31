/*
 * Copyright (C) 2012 Square, Inc.
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
package com.squareup.okhttp.internal.spdy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A locally-originated ping.
 */
public final class Ping {
  private final CountDownLatch latch = new CountDownLatch(1);
  private long sent = -1;
  private long received = -1;

  Ping() {
  }

  void send() {
    if (sent != -1) throw new IllegalStateException();
    sent = System.nanoTime();
  }

  void receive() {
    if (received != -1 || sent == -1) throw new IllegalStateException();
    received = System.nanoTime();
    latch.countDown();
  }

  void cancel() {
    if (received != -1 || sent == -1) throw new IllegalStateException();
    received = sent - 1;
    latch.countDown();
  }

  /**
   * Returns the round trip time for this ping in nanoseconds, waiting for the
   * response to arrive if necessary. Returns -1 if the response was
   * cancelled.
   */
  public long roundTripTime() throws InterruptedException {
    latch.await();
    return received - sent;
  }

  /**
   * Returns the round trip time for this ping in nanoseconds, or -1 if the
   * response was cancelled, or -2 if the timeout elapsed before the round
   * trip completed.
   */
  public long roundTripTime(long timeout, TimeUnit unit) throws InterruptedException {
    if (latch.await(timeout, unit)) {
      return received - sent;
    } else {
      return -2;
    }
  }
}
