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

import okhttp3.CertificatePinner
import java.security.cert.X509Certificate

fun <E : Throwable> E.withNetworkErrorLogging(errorType: ErrorType, vararg details: Pair<String, Any?>): E {
  return this.also {
    addSuppressed(NetworkError(NetworkErrorLogging(errorType, errorDetails = mapOf(*details))))
  }
}

val Throwable.errorDetails: NetworkErrorLogging?
  get() {
    val sequence: Sequence<Throwable> =
      generateSequence(this) { if (it.cause == it) null else it.cause }.flatMap { listOf(it) + it.suppressedExceptions }

    return sequence.map { if (it is NetworkError) it.details else null }.first()
  }

val NetworkErrorLogging.hostname: String?
  get() = errorDetails["hostname"] as? String

val NetworkErrorLogging.matchingPins: List<CertificatePinner.Pin>?
  get() = errorDetails["matchingPins"] as? List<CertificatePinner.Pin>

val NetworkErrorLogging.peerCertificates: List<X509Certificate>?
  get() = errorDetails["peerCertificates"] as? List<X509Certificate>

//class DnsNameNotResolvedException(
//  message: String? = null,
//  cause: Exception? = null,
//  host: String? = null
//) : UnknownHostException(message ?: cause?.message), TypedException {
//  init {
//    initCause(cause)
//  }
//
//  override val primaryErrorType = ErrorType.DNS_NAME_NOT_RESOLVED
//
//  val targetHostname: String? =
//    when {
//      host != null -> { host }
//      this.message?.endsWith(": Name or service not known") == true -> {
//        this.message.substringBefore(":")
//      }
//      else -> { null }
//    }


//class DnsFailedException(
//  message: String? = null,
//  cause: Exception? = null,
//  val targetHostname: String? = null
//) : UnknownHostException(message ?: cause?.message), TypedException {
//  init {
//    initCause(cause)
//  }
//
//  override val primaryErrorType = ErrorType.DNS_FAILED
//  override val errorDetails: Map<String, Any>
//    get() = if (targetHostname == null) mapOf() else mapOf("hostname" to targetHostname)
//}


//class CertPinnedKeyNotInCertChainException(
//  reason: String,
//  val hostname: String,
//  val matchingPins: List<CertificatePinner.Pin>,
//  val peerCertificates: List<X509Certificate>
//): SSLPeerUnverifiedException(reason), TypedException {
//  override val primaryErrorType = ErrorType.TLS_CERT_PINNED_KEY_NOT_IN_CERT_CHAIN
//  override val errorDetails: Map<String, Any>
//    get() = mapOf("hostname" to hostname, "matchingPins" to matchingPins, "peerCertificates" to peerCertificates)
//}