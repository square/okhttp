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
package okhttp3.curl

import com.github.ajalt.clikt.core.CliktCommand
import okhttp3.Call
import okhttp3.Request

expect class Main() : CliktCommand {
  val method: String?

  val data: String?

  val url: String?

  val referer: String?

  val headers: List<String>?

  val showHeaders: Boolean

  val userAgent: String

  var client: Call.Factory?

  override fun run()

  fun createClient(): Call.Factory

  fun createRequest(): Request

  internal fun close()
}
