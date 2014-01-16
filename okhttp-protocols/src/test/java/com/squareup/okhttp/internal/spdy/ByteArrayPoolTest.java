/*
 * Copyright (C) 2012 The Android Open Source Project
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

import org.junit.Test;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ByteArrayPoolTest {
  @Test public void testReusesBuffer() {
    ByteArrayPool pool = new ByteArrayPool(32);

    byte[] buf1 = pool.getBuf(16);
    byte[] buf2 = pool.getBuf(16);

    pool.returnBuf(buf1);
    pool.returnBuf(buf2);

    byte[] buf3 = pool.getBuf(16);
    byte[] buf4 = pool.getBuf(16);
    assertTrue(buf3 == buf1 || buf3 == buf2);
    assertTrue(buf4 == buf1 || buf4 == buf2);
    assertTrue(buf3 != buf4);
  }

  @Test public void testObeysSizeLimit() {
    ByteArrayPool pool = new ByteArrayPool(32);

    byte[] buf1 = pool.getBuf(16);
    byte[] buf2 = pool.getBuf(16);
    byte[] buf3 = pool.getBuf(16);

    pool.returnBuf(buf1);
    pool.returnBuf(buf2);
    pool.returnBuf(buf3);

    byte[] buf4 = pool.getBuf(16);
    byte[] buf5 = pool.getBuf(16);
    byte[] buf6 = pool.getBuf(16);

    assertTrue(buf4 == buf2 || buf4 == buf3);
    assertTrue(buf5 == buf2 || buf5 == buf3);
    assertTrue(buf4 != buf5);
    assertTrue(buf6 != buf1 && buf6 != buf2 && buf6 != buf3);
  }

  @Test public void testReturnsBufferWithRightSize() {
    ByteArrayPool pool = new ByteArrayPool(32);

    byte[] buf1 = pool.getBuf(16);
    pool.returnBuf(buf1);

    byte[] buf2 = pool.getBuf(17);
    assertNotSame(buf2, buf1);

    byte[] buf3 = pool.getBuf(15);
    assertSame(buf3, buf1);
  }
}
