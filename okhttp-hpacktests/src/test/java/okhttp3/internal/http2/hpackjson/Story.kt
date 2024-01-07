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
package okhttp3.internal.http2.hpackjson

/**
 * Representation of one story, a set of request headers to encode or decode. This class is used
 * reflectively with Moshi to parse stories from files.
 */
data class Story(
  val description: String? = null,
  val cases: List<Case>,
  val fileName: String? = null,
) {
  // Used as the test name.
  override fun toString() = fileName ?: "?"

  companion object {
    @JvmField
    val MISSING = Story(description = "Missing", cases = listOf(), "missing")
  }
}
