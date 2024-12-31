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

import okhttp3.survey.types.Client
import okhttp3.survey.types.SuiteId

/**
 * Organizes information on SSL cipher suite inclusion and precedence for this spreadsheet.
 * https://docs.google.com/spreadsheets/d/1C3FdZSlCBq_-qrVwG1KDIzNIB3Hyg_rKAcgmSzOsHyQ/edit#gid=0
 */
class CipherSuiteSurvey(
  val clients: List<Client>,
  val ianaSuites: IanaSuites,
  val orderBy: List<SuiteId>,
) {
  fun printGoogleSheet() {
    print("name")
    for (client in clients) {
      print("\t")
      print(client.nameAndVersion)
    }
    println()
    val sortedSuites =
      ianaSuites.suites.sortedBy { ianaSuite ->
        val index = orderBy.indexOfFirst { it.matches(ianaSuite) }
        if (index == -1) Integer.MAX_VALUE else index
      }
    for (suiteId in sortedSuites) {
      print(suiteId.name)
      for (client in clients) {
        print("\t")
        val index = client.enabled.indexOfFirst { it.matches(suiteId) }
        if (index != -1) {
          print(index + 1)
        }
      }
      println()
    }
  }
}
