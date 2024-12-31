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
package okhttp3.internal.http2

import assertk.assertThat
import assertk.assertions.isEqualTo
import okhttp3.internal.http2.hpackjson.HpackJsonUtil
import okhttp3.internal.http2.hpackjson.Story
import okio.Buffer

/**
 * Tests Hpack implementation using https://github.com/http2jp/hpack-test-case/
 */
open class HpackDecodeTestBase {
  private val bytesIn = Buffer()
  private val hpackReader = Hpack.Reader(bytesIn, 4096)

  protected fun testDecoder(story: Story) {
    for (testCase in story.cases) {
      val encoded = testCase.wire ?: continue
      bytesIn.write(encoded)
      hpackReader.readHeaders()
      assertSetEquals(
        "seqno=$testCase.seqno",
        testCase.headersList,
        hpackReader.getAndResetHeaderList(),
      )
    }
  }

  companion object {
    /**
     * Reads all stories in the folders provided, asserts if no story found.
     */
    @JvmStatic
    protected fun createStories(interopTests: Array<String>): List<Any> {
      if (interopTests.isEmpty()) return listOf<Any>(Story.MISSING)

      val result = mutableListOf<Any>()
      for (interopTestName in interopTests) {
        val stories = HpackJsonUtil.readStories(interopTestName)
        result.addAll(stories)
      }
      return result
    }

    /**
     * Checks if `expected` and `observed` are equal when viewed as a set and headers are
     * deduped.
     *
     * TODO: See if duped headers should be preserved on decode and verify.
     */
    private fun assertSetEquals(
      message: String,
      expected: List<Header>,
      observed: List<Header>,
    ) {
      assertThat(LinkedHashSet(observed), message)
        .isEqualTo(LinkedHashSet(expected))
    }
  }
}
