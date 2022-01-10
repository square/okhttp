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

package okhttp3.internal

import okhttp3.Certificate
import okhttp3.Handshake

internal fun Handshake.equals(other: Any?): Boolean {
  return other is Handshake &&
    other.tlsVersion == tlsVersion &&
    other.cipherSuite == cipherSuite &&
    other.peerCertificates == peerCertificates &&
    other.localCertificates == localCertificates
}

internal fun Handshake.hashCode(): Int {
  var result = 17
  result = 31 * result + tlsVersion.hashCode()
  result = 31 * result + cipherSuite.hashCode()
  result = 31 * result + peerCertificates.hashCode()
  result = 31 * result + localCertificates.hashCode()
  return result
}

internal fun Handshake.toString(): String {
  val peerCertificatesString = peerCertificates.map { it.name }.toString()
  return "Handshake{" +
    "tlsVersion=$tlsVersion " +
    "cipherSuite=$cipherSuite " +
    "peerCertificates=$peerCertificatesString " +
    "localCertificates=${localCertificates.map { it.name }}}"
}

internal expect val Certificate.name: String
