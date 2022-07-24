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

import java.security.Security
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.Path.Companion.toPath
import org.conscrypt.Conscrypt

suspend fun main() {
  Security.addProvider(Conscrypt.newProvider())

  val client = OkHttpClient()

  try {
    val ianaSuitesOld = ianaSuitesJuly2019
    val ianaSuitesNew = fetchIanaSuites(client)

    // TODO grab live config from
    // https://ssl-config.mozilla.org/guidelines/5.6.json

    // TODO load old OkHttp versions

    val clients = listOf(
      conscrypt(ianaSuitesNew),
      currentVm(ianaSuitesNew),
      currentOkHttp(ianaSuitesNew),
      firefox65, // existing
      chrome64, // existing
      chrome72, // existing
      android5,
      android9,
      chrome65,
      chrome70,
      chrome80,
      firefox72,
      java7,
      java12,
      edge18,
    )

    val survey = CipherSuiteSurvey(clients, ianaSuitesNew)

    // survey.showIanaDiff(ianaSuitesOld, ianaSuitesNew)
    // println()
    //
    // survey.printWarnings()
    // println()
    //
    // survey.printGoogleSheet()
    // println()

    survey.printCipherSuiteKt()
    println()
  } finally {
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
  }
}
