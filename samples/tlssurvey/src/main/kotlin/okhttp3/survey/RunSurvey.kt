/*
 * Copyright (C) 2018 Square, Inc.
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

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.survey.ssllabs.SslLabsScraper
import okio.FileSystem
import okio.Path.Companion.toPath

suspend fun main() {
//  Security.addProvider(Conscrypt.newProvider())

  val client = OkHttpClient.Builder()
    .cache(Cache("build/okhttp_cache".toPath(), 100_000_000, FileSystem.SYSTEM))
    .build()

  val sslLabsScraper = SslLabsScraper(client)

  try {
    val ianaSuitesOld = ianaSuitesJuly2019
    val ianaSuitesNew = fetchIanaSuites(client)

    val sslLabsClients = sslLabsScraper.query()

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
    val edge18 = sslLabsClients.first { it.userAgent == "Edge" && it.version == "18" }

    val clients = listOf(
      conscrypt(ianaSuitesNew),
      currentVm(ianaSuitesNew),
      currentOkHttp(ianaSuitesNew),
      android5,
      android9,
      java7,
      java12,
      firefox34,
      firefox53,
      firefox73,
      chrome33,
      chrome57,
      chrome80,
      edge18,
    )

    val survey = CipherSuiteSurvey(clients, ianaSuitesNew)

    // survey.showIanaDiff(ianaSuitesOld, ianaSuitesNew)
    // println()

//     survey.printWarnings()
//     println()

     survey.printGoogleSheet()
     println()

//    survey.printCipherSuiteKt()
    println()
  } finally {
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }
}
