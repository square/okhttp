/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.okhttp.internal.http;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertSame;

public class RouteExceptionTest {

  @Test public void getConnectionIOException_single() {
    IOException firstException = new IOException();
    RouteException re = new RouteException(firstException);
    assertSame(firstException, re.getLastConnectException());
  }

  @Test public void getConnectionIOException_multiple() {
    IOException firstException = new IOException();
    IOException secondException = new IOException();
    IOException thirdException = new IOException();
    RouteException re = new RouteException(firstException);
    re.addConnectException(secondException);
    re.addConnectException(thirdException);

    IOException connectionIOException = re.getLastConnectException();
    assertSame(thirdException, connectionIOException);
    Throwable[] thirdSuppressedExceptions = thirdException.getSuppressed();
    assertSame(secondException, thirdSuppressedExceptions[0]);

    Throwable[] secondSuppressedException = secondException.getSuppressed();
    assertSame(firstException, secondSuppressedException[0]);
  }
}
