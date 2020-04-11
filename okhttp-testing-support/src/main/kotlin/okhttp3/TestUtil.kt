/*
 * Copyright (C) 2018 Square, Inc.
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
package okhttp3

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.Arrays
import okhttp3.internal.http2.Header
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNoException

object TestUtil {
  @JvmField
  val UNREACHABLE_ADDRESS = InetSocketAddress("198.51.100.1", 8080)

  @JvmStatic
  fun headerEntries(vararg elements: String?): List<Header> {
    return List(elements.size / 2) { Header(elements[it * 2]!!, elements[it * 2 + 1]!!) }
  }

  @JvmStatic
  fun repeat(
    c: Char,
    count: Int
  ): String {
    val array = CharArray(count)
    Arrays.fill(array, c)
    return String(array)
  }

  /**
   * See FinalizationTester for discussion on how to best trigger GC in tests.
   * https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
   * java/lang/ref/FinalizationTester.java
   */
  @Throws(Exception::class)
  @JvmStatic
  fun awaitGarbageCollection() {
    Runtime.getRuntime().gc()
    Thread.sleep(100)
    System.runFinalization()
  }

  @JvmStatic
  fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      assumeNoException(uhe)
    }
  }

  @JvmStatic
  fun assumeNotWindows() {
    assumeFalse("This test fails on Windows.", windows)
  }

  @JvmStatic
  val windows: Boolean
    get() = System.getProperty("os.name", "?").startsWith("Windows")
}
