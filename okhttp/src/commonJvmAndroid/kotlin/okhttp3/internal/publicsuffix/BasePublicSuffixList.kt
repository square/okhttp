/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.internal.publicsuffix

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.internal.platform.Platform
import okio.ByteString
import okio.Source
import okio.buffer

internal abstract class BasePublicSuffixList : PublicSuffixList {
  /** True after we've attempted to read the list for the first time. */
  private val listRead = AtomicBoolean(false)

  /** Used for concurrent threads reading the list for the first time. */
  private val readCompleteLatch = CountDownLatch(1)

  // The lists are held as a large array of UTF-8 bytes. This is to avoid allocating lots of strings
  // that will likely never be used. Each rule is separated by '\n'. Please see the
  // PublicSuffixListGenerator class for how these lists are generated.
  // Guarded by this.
  override lateinit var bytes: ByteString
  override lateinit var exceptionBytes: ByteString

  @Throws(IOException::class)
  private fun readTheList() {
    var publicSuffixListBytes: ByteString?
    var publicSuffixExceptionListBytes: ByteString?

    try {
      listSource().buffer().use { bufferedSource ->
        val totalBytes = bufferedSource.readInt()
        publicSuffixListBytes = bufferedSource.readByteString(totalBytes.toLong())

        val totalExceptionBytes = bufferedSource.readInt()
        publicSuffixExceptionListBytes = bufferedSource.readByteString(totalExceptionBytes.toLong())
      }

      synchronized(this) {
        this.bytes = publicSuffixListBytes!!
        this.exceptionBytes = publicSuffixExceptionListBytes!!
      }
    } finally {
      readCompleteLatch.countDown()
    }
  }

  abstract fun listSource(): Source

  override fun ensureLoaded() {
    if (!listRead.get() && listRead.compareAndSet(false, true)) {
      readTheListUninterruptibly()
    } else {
      try {
        readCompleteLatch.await()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt() // Retain interrupted status.
      }
    }

    check(::bytes.isInitialized) {
      // May have failed with an IOException
      "Unable to load $path resource."
    }
  }

  abstract val path: Any

  /**
   * Reads the public suffix list treating the operation as uninterruptible. We always want to read
   * the list otherwise we'll be left in a bad state. If the thread was interrupted prior to this
   * operation, it will be re-interrupted after the list is read.
   */
  private fun readTheListUninterruptibly() {
    var interrupted = false
    try {
      while (true) {
        try {
          readTheList()
          return
        } catch (_: InterruptedIOException) {
          Thread.interrupted() // Temporarily clear the interrupted state.
          interrupted = true
        } catch (e: IOException) {
          Platform.get().log("Failed to read public suffix list", Platform.Companion.WARN, e)
          return
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt() // Retain interrupted status.
      }
    }
  }
}
