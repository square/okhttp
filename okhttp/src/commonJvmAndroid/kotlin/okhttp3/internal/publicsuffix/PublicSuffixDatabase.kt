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

import java.net.IDN
import okhttp3.internal.and
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * A database of public suffixes provided by [publicsuffix.org][publicsuffix_org].
 *
 * [publicsuffix_org]: https://publicsuffix.org/
 */
class PublicSuffixDatabase internal constructor(
  private val publicSuffixList: PublicSuffixList,
) {
  /**
   * Returns the effective top-level domain plus one (eTLD+1) by referencing the public suffix list.
   * Returns null if the domain is a public suffix or a private address.
   *
   * Here are some examples:
   *
   * ```java
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
    val domainLabels = splitDomain(unicodeDomain)

    val rule = findMatchingRule(domainLabels)
    if (domainLabels.size == rule.size && rule[0][0] != EXCEPTION_MARKER) {
      return null // The domain is a public suffix.
    }

    val firstLabelOffset =
      if (rule[0][0] == EXCEPTION_MARKER) {
        // Exception rules hold the effective TLD plus one.
        domainLabels.size - rule.size
      } else {
        // Otherwise the rule is for a public suffix, so we must take one more label.
        domainLabels.size - (rule.size + 1)
      }

    return splitDomain(domain).asSequence().drop(firstLabelOffset).joinToString(".")
  }

  private fun splitDomain(domain: String): List<String> {
    val domainLabels = domain.split('.')

    if (domainLabels.last() == "") {
      // allow for domain name trailing dot
      return domainLabels.dropLast(1)
    }

    return domainLabels
  }

  private fun findMatchingRule(domainLabels: List<String>): List<String> {
    publicSuffixList.ensureLoaded()

    // Break apart the domain into UTF-8 labels, i.e. foo.bar.com turns into [foo, bar, com].
    val domainLabelsUtf8Bytes = Array(domainLabels.size) { i -> domainLabels[i].encodeUtf8() }

    // Start by looking for exact matches. We start at the leftmost label. For example, foo.bar.com
    // will look like: [foo, bar, com], [bar, com], [com]. The longest matching rule wins.
    var exactMatch: String? = null
    for (i in domainLabelsUtf8Bytes.indices) {
      val rule = publicSuffixList.bytes.binarySearch(domainLabelsUtf8Bytes, i)
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
        val rule = publicSuffixList.bytes.binarySearch(labelsWithWildcard, labelIndex)
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
        val rule =
          publicSuffixList.exceptionBytes.binarySearch(
            domainLabelsUtf8Bytes,
            labelIndex,
          )
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

  companion object {
    private val WILDCARD_LABEL = ByteString.of('*'.code.toByte())
    private val PREVAILING_RULE = listOf("*")

    private const val EXCEPTION_MARKER = '!'

    private val instance = PublicSuffixDatabase(PublicSuffixList.Default)

    fun get(): PublicSuffixDatabase {
      return instance
    }

    private fun ByteString.binarySearch(
      labels: Array<ByteString>,
      labelIndex: Int,
    ): String? {
      var low = 0
      var high = size
      var match: String? = null
      while (low < high) {
        var mid = (low + high) / 2
        // Search for a '\n' that marks the start of a value. Don't go back past the start of the
        // array.
        while (mid > -1 && this[mid] != '\n'.code.toByte()) {
          mid--
        }
        mid++

        // Now look for the ending '\n'.
        var end = 1
        while (this[mid + end] != '\n'.code.toByte()) {
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
            byte0 = '.'.code
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
            match = this.substring(mid, mid + publicSuffixLength).string(Charsets.UTF_8)
            break
          }
        }
      }
      return match
    }
  }
}
