/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.stress

internal data class Stats(
  val nanotime: Long = 0L,
  val clientRequests: Long = 0L,
  val clientExceptions: Long = 0L,
  val clientFailures: Long = 0L,
  val clientResponses: Long = 0L,
  val clientRequestBytes: Long = 0L,
  val clientResponseBytes: Long = 0L,
  val clientHttp1Responses: Long = 0L,
  val clientHttp2Responses: Long = 0L,
  val serverResponses: Long = 0L,
  val serverRequestBytes: Long = 0L,
  val serverResponseBytes: Long = 0L,
  val totalBytesRead: Long = 0L,
  val acknowledgedBytesRead: Long = 0L,
  val writeBytesTotal: Long = 0L,
  val writeBytesMaximum: Long = 0L
) {
  fun plus(other: Stats): Stats {
    return Stats(
        clientRequests = clientRequests + other.clientRequests,
        clientExceptions = clientExceptions + other.clientExceptions,
        clientFailures = clientFailures + other.clientFailures,
        clientResponses = clientResponses + other.clientResponses,
        clientRequestBytes = clientRequestBytes + other.clientRequestBytes,
        clientResponseBytes = clientResponseBytes + other.clientResponseBytes,
        clientHttp1Responses = clientHttp1Responses + other.clientHttp1Responses,
        clientHttp2Responses = clientHttp2Responses + other.clientHttp2Responses,
        serverResponses = serverResponses + other.serverResponses,
        serverRequestBytes = serverRequestBytes + other.serverRequestBytes,
        serverResponseBytes = serverResponseBytes + other.serverResponseBytes,
        totalBytesRead = totalBytesRead + other.totalBytesRead,
        acknowledgedBytesRead = acknowledgedBytesRead + other.acknowledgedBytesRead,
        writeBytesTotal = writeBytesTotal + other.writeBytesTotal,
        writeBytesMaximum = writeBytesMaximum + other.writeBytesMaximum
    )
  }
}
