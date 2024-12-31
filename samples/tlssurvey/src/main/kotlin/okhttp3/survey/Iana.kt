/*
 * Copyright (C) 2022 Square, Inc.
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
package okhttp3.survey

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import okhttp3.survey.types.SuiteId
import okio.ByteString.Companion.decodeHex
import okio.IOException

/** Example: "0x00,0x08",TLS_RSA_EXPORT_WITH_DES40_CBC_SHA,Y,N,[RFC4346] */
val IANA_CSV_PATTERN = "\"0x(\\w\\w),0x(\\w\\w)\",(\\w+).*".toRegex()

fun parseIanaCsvRow(s: String): SuiteId? {
  if (s.contains("Reserved") || s.contains("Unassigned")) return null
  val matcher = IANA_CSV_PATTERN.matchEntire(s) ?: return null
  val id = (matcher.groupValues[1] + matcher.groupValues[2]).decodeHex()
  return SuiteId(id, matcher.groupValues[3])
}

class IanaSuites(
  val name: String,
  val suites: List<SuiteId>,
) {
  fun fromJavaName(javaName: String): SuiteId {
    return suites.firstOrNull {
      it.name == javaName || it.name == "TLS_${javaName.drop(4)}"
    } ?: throw IllegalArgumentException("No such suite: $javaName")
  }
}

suspend fun fetchIanaSuites(okHttpClient: OkHttpClient): IanaSuites {
  val url = "https://www.iana.org/assignments/tls-parameters/tls-parameters-4.csv"

  val call = okHttpClient.newCall(Request(url.toHttpUrl()))

  val suites =
    call.executeAsync().use {
      if (!it.isSuccessful) {
        throw IOException("Failed ${it.code} ${it.message}")
      }
      it.body.string().lines()
        .mapNotNull { parseIanaCsvRow(it) }
    }

  return IanaSuites("current", suites)
}
