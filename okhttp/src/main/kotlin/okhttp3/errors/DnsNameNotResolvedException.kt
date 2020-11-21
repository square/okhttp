/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.errors

import okhttp3.errors.ErrorType.Companion.DNS_NAME_NOT_RESOLVED
import java.net.UnknownHostException

class DnsNameNotResolvedException(
  message: String? = null,
  cause: Exception? = null,
  host: String? = null
) : UnknownHostException(message ?: cause?.message), TypedException {
  init {
    initCause(cause)
  }

  override val primaryErrorType = DNS_NAME_NOT_RESOLVED

  val targetHostname: String? =
    when {
      host != null -> { host }
      this.message?.endsWith(": Name or service not known") == true -> {
        this.message.substringBefore(":")
      }
      else -> { null }
    }

  override val errorDetails: Map<String, Any>
    get() = if (targetHostname == null) mapOf() else mapOf("hostname" to targetHostname)
}
