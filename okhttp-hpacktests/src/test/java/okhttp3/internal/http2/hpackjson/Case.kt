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

import okhttp3.internal.http2.Header
import okio.ByteString

/**
 * Representation of an individual case (set of headers and wire format). There are many cases for a
 * single story.  This class is used reflectively with Moshi to parse stories.
 */
data class Case(
  val seqno: Int = 0,
  val wire: ByteString? = null,
  val headers: List<Map<String, String>>,
) : Cloneable {
  val headersList: List<Header>
    get() {
      val result = mutableListOf<Header>()
      for (inputHeader in headers) {
        val (key, value) = inputHeader.entries.iterator().next()
        result.add(Header(key, value))
      }
      return result
    }

  public override fun clone() = Case(seqno, this.wire, headers)
}
