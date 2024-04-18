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

import java.util.SortedSet
import java.util.TreeSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import okhttp3.internal.publicsuffix.PublicSuffixDatabase.Companion.PUBLIC_SUFFIX_RESOURCE
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.gzip

/**
 * Downloads the public suffix list from https://publicsuffix.org/list/public_suffix_list.dat and
 * transforms the file into an efficient format used by OkHttp.
 *
 *
 * The intent is to use this class to update the list periodically by manually running the main
 * method. This should be run from the top-level okhttp directory.
 *
 *
 * The resulting file is used by [PublicSuffixDatabase].
 */
class PublicSuffixListGenerator(
  projectRoot: Path = ".".toPath(),
  val fileSystem: FileSystem = FileSystem.SYSTEM,
  val client: OkHttpClient = OkHttpClient(),
) {
  private val resources = projectRoot / "okhttp/src/main/resources/okhttp3/internal/publicsuffix"
  private val testResources = projectRoot / "okhttp/src/test/resources/okhttp3/internal/publicsuffix"
  private val publicSuffixListDotDat = testResources / "public_suffix_list.dat"
  private val outputFile = resources / PUBLIC_SUFFIX_RESOURCE

  val request = Request("https://publicsuffix.org/list/public_suffix_list.dat".toHttpUrl())

  suspend fun import() {
    check(fileSystem.metadata(resources).isDirectory)
    check(fileSystem.metadata(testResources).isDirectory)

    updateLocalFile()

    val importResults = readImportResults()

    writeOutputFile(importResults)
  }

  private suspend fun updateLocalFile() =
    withContext(Dispatchers.IO) {
      client.newCall(request).executeAsync().use { response ->
        fileSystem.sink(publicSuffixListDotDat).buffer().use { sink ->
          sink.writeAll(response.body.source())
        }
      }
    }

  private suspend fun readImportResults(): ImportResults =
    withContext(Dispatchers.IO) {
      val sortedRules: SortedSet<ByteString> = TreeSet()
      val sortedExceptionRules: SortedSet<ByteString> = TreeSet()
      var totalRuleBytes = 0
      var totalExceptionRuleBytes = 0

      fileSystem.source(publicSuffixListDotDat).buffer().use { source ->
        while (!source.exhausted()) {
          var rule: ByteString = source.readUtf8LineStrict().toRule() ?: continue

          if (rule.startsWith(EXCEPTION_RULE_MARKER)) {
            rule = rule.substring(1)
            // We use '\n' for end of value.
            totalExceptionRuleBytes += rule.size + 1
            sortedExceptionRules.add(rule)
          } else {
            totalRuleBytes += rule.size + 1 // We use '\n' for end of value.
            sortedRules.add(rule)
          }
        }
      }

      ImportResults(sortedRules, sortedExceptionRules, totalRuleBytes, totalExceptionRuleBytes)
    }

  private fun String.toRule(): ByteString? {
    if (trim { it <= ' ' }.isEmpty() || startsWith("//")) return null
    if (contains(WILDCARD_CHAR)) {
      assertWildcardRule(this)
    }
    return encodeUtf8()
  }

  data class ImportResults(
    val sortedRules: SortedSet<ByteString>,
    val sortedExceptionRules: SortedSet<ByteString>,
    val totalRuleBytes: Int,
    val totalExceptionRuleBytes: Int,
  ) {
    fun writeOut(sink: BufferedSink) {
      with(sink) {
        writeInt(totalRuleBytes)
        for (domain in sortedRules) {
          write(domain).writeByte(NEWLINE)
        }
        writeInt(totalExceptionRuleBytes)
        for (domain in sortedExceptionRules) {
          write(domain).writeByte(NEWLINE)
        }
      }
    }
  }

  private suspend fun writeOutputFile(importResults: ImportResults) =
    withContext(Dispatchers.IO) {
      fileSystem.sink(outputFile).gzip().buffer().use { sink ->
        importResults.writeOut(sink)
      }
    }

  /**
   * These assertions ensure the [PublicSuffixDatabase] remains correct. The specification is
   * very flexible regarding wildcard rules, but this flexibility is not something currently used
   * in practice. To simplify the implementation, we've avoided implementing the flexible rules in
   * favor of supporting what's actually used in practice. That means if these assertions ever fail,
   * the implementation will need to be revisited to support a more flexible rule.
   */
  private fun assertWildcardRule(rule: String) {
    check(rule.startsWith(WILDCARD_CHAR)) {
      """Wildcard Assertion Failure: '$rule'
A wildcard rule was added with a wildcard that is not in leftmost position! We'll need to change the ${PublicSuffixDatabase::class.java.name} to handle this."""
    }
    check(rule.indexOf(WILDCARD_CHAR, 1) == -1) {
      """Wildcard Assertion Failure: '$rule'
A wildcard rule was added with multiple wildcards! We'll need to change ${PublicSuffixDatabase::class.java.name} to handle this."""
    }
    check(rule.length != 1) {
      """Wildcard Assertion Failure: '$rule'
A wildcard rule was added that wildcards the first level! We'll need to change the ${PublicSuffixDatabase::class.java.name} to handle this."""
    }
  }

  companion object {
    private const val NEWLINE = '\n'.code
    private const val WILDCARD_CHAR = "*"
    private val EXCEPTION_RULE_MARKER: ByteString = "!".encodeUtf8()
  }
}

suspend fun main() {
  PublicSuffixListGenerator().import()
}
