/*
 * Copyright (C) 2017 Square, Inc.
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

import okhttp3.internal.and
import okhttp3.internal.platform.Platform
import okio.GzipSource
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InterruptedIOException
import java.net.IDN
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A database of public suffixes provided by [publicsuffix.org][publicsuffix_org].
 *
 * [publicsuffix_org]: https://publicsuffix.org/
 */
class PublicSuffixDatabase {

  /** True after we've attempted to read the list for the first time. */
  private val listRead = AtomicBoolean(false)

  /** Used for concurrent threads reading the list for the first time. */
  private val readCompleteLatch = CountDownLatch(1)

  // The lists are held as a large array of UTF-8 bytes. This is to avoid allocating lots of strings
  // that will likely never be used. Each rule is separated by '\n'. Please see the
  // PublicSuffixListGenerator class for how these lists are generated.
  // Guarded by this.
  private lateinit var publicSuffixListBytes: ByteArray
  private lateinit var publicSuffixExceptionListBytes: ByteArray

  /**
   * Returns the effective top-level domain plus one (eTLD+1) by referencing the public suffix list.
   * Returns null if the domain is a public suffix or a private address.
   *
   * Here are some examples:
   *
   * ```
   * assertEquals("google.com", getEffectiveTldPlusOne("google.com"));
   * assertEquals("google.com", getEffectiveTldPlusOne("www.google.com"));
   * assertNull(getEffectiveTldPlusOne("com"));
   * assertNull(getEffectiveTldPlusOne("localhost"));
   * assertNull(getEffectiveTldPlusOne("mymacbook"));
   * ```
   *
   * @param domain A canonicalized domain. An International Domain Name (IDN) should be punycode
   *     encoded.
   */
  fun getEffectiveTldPlusOne(domain: String): String? {
    // We use UTF-8 in the list so we need to convert to Unicode.
    val unicodeDomain = IDN.toUnicode(domain)
    val domainLabels = unicodeDomain.split('.')
    val rule = findMatchingRule(domainLabels)
    if (domainLabels.size == rule.size && rule[0][0] != EXCEPTION_MARKER) {
      return null // The domain is a public suffix.
    }

    val firstLabelOffset = if (rule[0][0] == EXCEPTION_MARKER) {
      // Exception rules hold the effective TLD plus one.
      domainLabels.size - rule.size
    } else {
      // Otherwise the rule is for a public suffix, so we must take one more label.
      domainLabels.size - (rule.size + 1)
    }

    return domain.split('.').asSequence().drop(firstLabelOffset).joinToString(".")
  }

  private fun findMatchingRule(domainLabels: List<String>): List<String> {
    if (!listRead.get() && listRead.compareAndSet(false, true)) {
      readTheListUninterruptibly()
    } else {
      try {
        readCompleteLatch.await()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt() // Retain interrupted status.
      }
    }

    check(::publicSuffixListBytes.isInitialized) {
      "Unable to load $PUBLIC_SUFFIX_RESOURCE resource from the classpath."
    }

    // Break apart the domain into UTF-8 labels, i.e. foo.bar.com turns into [foo, bar, com].
    val domainLabelsUtf8Bytes = Array(domainLabels.size) { i -> domainLabels[i].toByteArray(UTF_8) }

    // Start by looking for exact matches. We start at the leftmost label. For example, foo.bar.com
    // will look like: [foo, bar, com], [bar, com], [com]. The longest matching rule wins.
    var exactMatch: String? = null
    for (i in domainLabelsUtf8Bytes.indices) {
      val rule = publicSuffixListBytes.binarySearch(domainLabelsUtf8Bytes, i)
      if (rule != null) {
        exactMatch = rule
        break
      }
    }

    // In theory, wildcard rules are not restricted to having the wildcard in the leftmost position.
    // In practice, wildcards are always in the leftmost position. For now, this implementation
    // cheats and does not attempt every possible permutation. Instead, it only considers wildcards
    // in the leftmost position. We assert this fact when we generate the public suffix file. If
    // this assertion ever fails we'll need to refactor this implementation.
    var wildcardMatch: String? = null
    if (domainLabelsUtf8Bytes.size > 1) {
      val labelsWithWildcard = domainLabelsUtf8Bytes.clone()
      for (labelIndex in 0 until labelsWithWildcard.size - 1) {
        labelsWithWildcard[labelIndex] = WILDCARD_LABEL
        val rule = publicSuffixListBytes.binarySearch(labelsWithWildcard, labelIndex)
        if (rule != null) {
          wildcardMatch = rule
          break
        }
      }
    }

    // Exception rules only apply to wildcard rules, so only try it if we matched a wildcard.
    var exception: String? = null
    if (wildcardMatch != null) {
      for (labelIndex in 0 until domainLabelsUtf8Bytes.size - 1) {
        val rule = publicSuffixExceptionListBytes.binarySearch(
            domainLabelsUtf8Bytes, labelIndex)
        if (rule != null) {
          exception = rule
          break
        }
      }
    }

    if (exception != null) {
      // Signal we've identified an exception rule.
      exception = "!$exception"
      return exception.split('.')
    } else if (exactMatch == null && wildcardMatch == null) {
      return PREVAILING_RULE
    }

    val exactRuleLabels = exactMatch?.split('.') ?: listOf()
    val wildcardRuleLabels = wildcardMatch?.split('.') ?: listOf()

    return if (exactRuleLabels.size > wildcardRuleLabels.size) {
      exactRuleLabels
    } else {
      wildcardRuleLabels
    }
  }

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
          Platform.get().log("Failed to read public suffix list", Platform.WARN, e)
          return
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt() // Retain interrupted status.
      }
    }
  }

  @Throws(IOException::class)
  private fun readTheList() {
    var publicSuffixListBytes: ByteArray? = null
    var publicSuffixExceptionListBytes: ByteArray? = null

    val resource =
        PublicSuffixDatabase::class.java.getResourceAsStream(PUBLIC_SUFFIX_RESOURCE) ?: return

    GzipSource(resource.source()).buffer().use { bufferedSource ->
      val totalBytes = bufferedSource.readInt()
      publicSuffixListBytes = bufferedSource.readByteArray(totalBytes.toLong())

      val totalExceptionBytes = bufferedSource.readInt()
      publicSuffixExceptionListBytes = bufferedSource.readByteArray(totalExceptionBytes.toLong())
    }

    synchronized(this) {
      this.publicSuffixListBytes = publicSuffixListBytes!!
      this.publicSuffixExceptionListBytes = publicSuffixExceptionListBytes!!
    }

    readCompleteLatch.countDown()
  }

  /** Visible for testing. */
  fun setListBytes(
    publicSuffixListBytes: ByteArray,
    publicSuffixExceptionListBytes: ByteArray
  ) {
    this.publicSuffixListBytes = publicSuffixListBytes
    this.publicSuffixExceptionListBytes = publicSuffixExceptionListBytes
    listRead.set(true)
    readCompleteLatch.countDown()
  }

  companion object {
    const val PUBLIC_SUFFIX_RESOURCE = "publicsuffixes.gz"

    private val WILDCARD_LABEL = byteArrayOf('*'.toByte())
    private val PREVAILING_RULE = listOf("*")

    private const val EXCEPTION_MARKER = '!'

    private val instance = PublicSuffixDatabase()

    fun get(): PublicSuffixDatabase {
      return instance
    }

    private fun ByteArray.binarySearch(
      labels: Array<ByteArray>,
      labelIndex: Int
    ): String? {
      var low = 0
      var high = size
      var match: String? = null
      while (low < high) {
        var mid = (low + high) / 2
        // Search for a '\n' that marks the start of a value. Don't go back past the start of the
        // array.
        while (mid > -1 && this[mid] != '\n'.toByte()) {
          mid--
        }
        mid++

        // Now look for the ending '\n'.
        var end = 1
        while (this[mid + end] != '\n'.toByte()) {
          end++
        }
        val publicSuffixLength = mid + end - mid

        // Compare the bytes. Note that the file stores UTF-8 encoded bytes, so we must compare the
        // unsigned bytes.
        var compareResult: Int
        var currentLabelIndex = labelIndex
        var currentLabelByteIndex = 0
        var publicSuffixByteIndex = 0

        var expectDot = false
        while (true) {
          val byte0: Int
          if (expectDot) {
            byte0 = '.'.toInt()
            expectDot = false
          } else {
            byte0 = labels[currentLabelIndex][currentLabelByteIndex] and 0xff
          }

          val byte1 = this[mid + publicSuffixByteIndex] and 0xff

          compareResult = byte0 - byte1
          if (compareResult != 0) break

          publicSuffixByteIndex++
          currentLabelByteIndex++
          if (publicSuffixByteIndex == publicSuffixLength) break

          if (labels[currentLabelIndex].size == currentLabelByteIndex) {
            // We've exhausted our current label. Either there are more labels to compare, in which
            // case we expect a dot as the next character. Otherwise, we've checked all our labels.
            if (currentLabelIndex == labels.size - 1) {
              break
            } else {
              currentLabelIndex++
              currentLabelByteIndex = -1
              expectDot = true
            }
          }
        }

        if (compareResult < 0) {
          high = mid - 1
        } else if (compareResult > 0) {
          low = mid + end + 1
        } else {
          // We found a match, but are the lengths equal?
          val publicSuffixBytesLeft = publicSuffixLength - publicSuffixByteIndex
          var labelBytesLeft = labels[currentLabelIndex].size - currentLabelByteIndex
          for (i in currentLabelIndex + 1 until labels.size) {
            labelBytesLeft += labels[i].size
          }

          if (labelBytesLeft < publicSuffixBytesLeft) {
            high = mid - 1
          } else if (labelBytesLeft > publicSuffixBytesLeft) {
            low = mid + end + 1
          } else {
            // Found a match.
            match = String(this, mid, publicSuffixLength, UTF_8)
            break
          }
        }
      }
      return match
    }
  }
}
