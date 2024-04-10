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

import java.security.Security
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.survey.ssllabs.SslLabsClient
import okhttp3.survey.types.Client
import okhttp3.survey.types.SuiteId
import okio.FileSystem
import okio.Path.Companion.toPath
import org.conscrypt.Conscrypt

@Suppress("ktlint:standard:property-naming")
suspend fun main() {
  val includeConscrypt = false

  val client =
    OkHttpClient.Builder()
      .cache(Cache(FileSystem.SYSTEM, "build/okhttp_cache".toPath(), 100_000_000))
      .build()

  val sslLabsClients = SslLabsClient(client).clients()
  val ianaSuitesNew = fetchIanaSuites(client)

  val android5 = sslLabsClients.first { it.userAgent == "Android" && it.version == "5.0.0" }
  val android9 = sslLabsClients.first { it.userAgent == "Android" && it.version == "9.0" }
  val chrome33 = sslLabsClients.first { it.userAgent == "Chrome" && it.version == "33" }
  val chrome57 = sslLabsClients.first { it.userAgent == "Chrome" && it.version == "57" }
  val chrome80 = sslLabsClients.first { it.userAgent == "Chrome" && it.version == "80" }
  val firefox34 = sslLabsClients.first { it.userAgent == "Firefox" && it.version == "34" }
  val firefox53 = sslLabsClients.first { it.userAgent == "Firefox" && it.version == "53" }
  val firefox73 = sslLabsClients.first { it.userAgent == "Firefox" && it.version == "73" }
  val java7 = sslLabsClients.first { it.userAgent == "Java" && it.version == "7u25" }
  val java12 = sslLabsClients.first { it.userAgent == "Java" && it.version == "12.0.1" }
  val safari12iOS = sslLabsClients.first { it.userAgent == "Safari" && it.platform == "iOS 12.3.1" }
  val safari12Osx =
    sslLabsClients.first { it.userAgent == "Safari" && it.platform == "MacOS 10.14.6 Beta" }

  val okhttp = currentOkHttp(ianaSuitesNew)

  val okHttp_4_10 = historicOkHttp("4.10")
  val okHttp_3_14 = historicOkHttp("3.14")
  val okHttp_3_13 = historicOkHttp("3.13")
  val okHttp_3_11 = historicOkHttp("3.11")
  val okHttp_3_9 = historicOkHttp("3.9")

  val currentVm = currentVm(ianaSuitesNew)

  val conscrypt =
    if (includeConscrypt) {
      Security.addProvider(Conscrypt.newProvider())
      conscrypt(ianaSuitesNew)
    } else {
      Client("Conscrypt", "Disabled", null, listOf())
    }

  val clients =
    listOf(
      okhttp,
      chrome80,
      firefox73,
      android9,
      safari12iOS,
      conscrypt,
      currentVm,
      okHttp_3_9,
      okHttp_3_11,
      okHttp_3_13,
      okHttp_3_14,
      okHttp_4_10,
      android5,
      java7,
      java12,
      firefox34,
      firefox53,
      chrome33,
      chrome57,
      safari12Osx,
    )

  val orderBy = okhttp.enabled + chrome80.enabled + safari12Osx.enabled + rest(clients)
  val survey = CipherSuiteSurvey(clients = clients, ianaSuites = ianaSuitesNew, orderBy = orderBy)

  survey.printGoogleSheet()
}

fun rest(clients: List<Client>): List<SuiteId> {
  // combine all ciphers to get these near the top
  return clients.flatMap { it.enabled }
}
