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
package okhttp3

import okhttp3.internal.format
import okio.Buffer
import okio.BufferedSource

/**
 * A test from the [Web Platform URL test suite](https://github.com/web-platform-tests/wpt/blob/master/url/resources/urltestdata.json).
 *
 * Each test is a line of the file `urltestdata.txt`. The format is informally specified by its
 * JavaScript parser `urltestparser.js` with which this class attempts to be compatible.
 *
 * Each line of the `urltestdata.txt` file specifies a test. Lines look like this:
 *
 * ```
 * http://example\t.\norg http://example.org/foo/bar s:http h:example.org p:/
 * ```
 */
class WebPlatformUrlTestData {
  var input: String? = null
  var base: String? = null
  var scheme = ""
  var username = ""
  var password: String? = null
  var host = ""
  var port = ""
  var path = ""
  var query = ""
  var fragment = ""

  fun expectParseFailure() = scheme.isEmpty()

  private operator fun set(
    name: String,
    value: String,
  ) {
    when (name) {
      "s" -> scheme = value
      "u" -> username = value
      "pass" -> password = value
      "h" -> host = value
      "port" -> port = value
      "p" -> path = value
      "q" -> query = value
      "f" -> fragment = value
      else -> throw IllegalArgumentException("unexpected attribute: $value")
    }
  }

  override fun toString(): String = format("Parsing: <%s> against <%s>", input!!, base!!)

  companion object {
    fun load(source: BufferedSource): List<WebPlatformUrlTestData> {
      val list = mutableListOf<WebPlatformUrlTestData>()
      while (true) {
        val line = source.readUtf8Line() ?: break
        if (line.isEmpty() || line.startsWith("#")) continue

        var i = 0
        val parts = line.split(Regex(" ")).toTypedArray()

        val element = WebPlatformUrlTestData()
        element.input = unescape(parts[i++])

        val base = if (i < parts.size) parts[i++] else null
        element.base =
          when {
            base == null || base.isEmpty() -> list[list.size - 1].base
            else -> unescape(base)
          }

        while (i < parts.size) {
          val piece = parts[i]
          if (piece.startsWith("#")) {
            i++
            continue
          }
          val nameAndValue = piece.split(Regex(":"), 2).toTypedArray()
          element[nameAndValue[0]] = unescape(nameAndValue[1])
          i++
        }

        list += element
      }
      return list
    }

    private fun unescape(s: String): String {
      return buildString {
        val buffer = Buffer().writeUtf8(s)
        while (!buffer.exhausted()) {
          val c = buffer.readUtf8CodePoint()
          if (c != '\\'.code) {
            append(c.toChar())
            continue
          }
          when (buffer.readUtf8CodePoint()) {
            '\\'.code -> append('\\')
            '#'.code -> append('#')
            'n'.code -> append('\n')
            'r'.code -> append('\r')
            's'.code -> append(' ')
            't'.code -> append('\t')
            'f'.code -> append('\u000c')
            'u'.code -> append(buffer.readUtf8(4).toInt(16).toChar())
            else -> throw IllegalArgumentException("unexpected escape character in $s")
          }
        }
      }
    }
  }
}
