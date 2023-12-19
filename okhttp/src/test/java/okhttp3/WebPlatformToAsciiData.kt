/*
 * Copyright (C) 2023 Block, Inc.
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

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * A test from the [Web Platform To ASCII](https://github.com/web-platform-tests/wpt/blob/master/url/resources/toascii.json).
 *
 * Each test is a line of the file `toascii.json`.
 */
@Serializable
class WebPlatformToAsciiData {
  var input: String? = null
  var output: String? = null
  var comment: String? = null

  override fun toString() = "input=$input output=$output"

  companion object {
    fun load(): List<WebPlatformToAsciiData> {
      val path = okHttpRoot / "okhttp/src/test/resources/web-platform-test-toascii.json"
      return SYSTEM_FILE_SYSTEM.read(path) {
        Json.decodeFromString<List<WebPlatformToAsciiData>>(readUtf8())
      }
    }
  }
}
