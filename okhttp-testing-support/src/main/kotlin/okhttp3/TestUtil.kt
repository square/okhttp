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

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.Arrays
import okhttp3.internal.http2.Header
import okio.Buffer
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue

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
   * Okio buffers are internally implemented as a linked list of arrays. Usually this implementation
   * detail is invisible to the caller, but subtle use of certain APIs may depend on these internal
   * structures.
   *
   * We make such subtle calls in [okhttp3.internal.ws.MessageInflater] because we try to read a
   * compressed stream that is terminated in a web socket frame even though the DEFLATE stream is
   * not terminated.
   *
   * Use this method to create a degenerate Okio Buffer where each byte is in a separate segment of
   * the internal list.
   */
  @JvmStatic
  fun fragmentBuffer(buffer: Buffer): Buffer {
    // Write each byte into a new buffer, then clone it so that the segments are shared.
    // Shared segments cannot be compacted so we'll get a long chain of short segments.
    val result = Buffer()
    while (!buffer.exhausted()) {
      val box = Buffer()
      box.write(buffer, 1)
      result.write(box.copy(), 1)
    }
    return result
  }

  tailrec fun File.isDescendentOf(directory: File): Boolean {
    val parentFile = parentFile ?: return false
    if (parentFile == directory) return true
    return parentFile.isDescendentOf(directory)
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
      assumeTrue(false, "requires network")
    }
  }

  @JvmStatic
  fun assumeNotWindows() {
    assumeFalse(windows, "This test fails on Windows.")
  }

  @JvmStatic
  val windows: Boolean
    get() = System.getProperty("os.name", "?").startsWith("Windows")
}
