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
package okio;

import java.nio.charset.Charset;

final class Util {
  /** A cheap and type-safe constant for the UTF-8 Charset. */
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  private Util() {
  }

  public static void checkOffsetAndCount(long arrayLength, long offset, long count) {
    if ((offset | count) < 0 || offset > arrayLength || arrayLength - offset < count) {
      throw new ArrayIndexOutOfBoundsException();
    }
  }

  public static short reverseBytesShort(short s) {
    int i = s & 0xffff;
    int reversed = (i & 0xff00) >>> 8
        |          (i & 0x00ff)  << 8;
    return (short) reversed;
  }

  public static int reverseBytesInt(int i) {
    return (i & 0xff000000) >>> 24
        |  (i & 0x00ff0000) >>>  8
        |  (i & 0x0000ff00)  <<  8
        |  (i & 0x000000ff)  << 24;
  }

  public static long reverseBytesLong(long v) {
    return (v & 0xff00000000000000L) >>> 56
        |  (v & 0x00ff000000000000L) >>> 40
        |  (v & 0x0000ff0000000000L) >>> 24
        |  (v & 0x000000ff00000000L) >>>  8
        |  (v & 0x00000000ff000000L)  <<  8
        |  (v & 0x0000000000ff0000L)  << 24
        |  (v & 0x000000000000ff00L)  << 40
        |  (v & 0x00000000000000ffL)  << 56;
  }

  /**
   * Throws {@code t}, even if the declared throws clause doesn't permit it.
   * This is a terrible – but terribly convenient – hack that makes it easy to
   * catch and rethrow exceptions after cleanup. See Java Puzzlers #43.
   */
  public static void sneakyRethrow(Throwable t) {
    Util.<Error>sneakyThrow2(t);
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow2(Throwable t) throws T {
    throw (T) t;
  }
}
