/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3.dnsoverhttps

import java.net.InetAddress
import okio.ByteString

internal data class DnsMessage(
  val id: Short,
  val flags: Int,
  val questions: List<Question>,
  val answers: List<ResourceRecord>,
  val authorityRecords: List<ResourceRecord> = listOf(),
  val additionalRecords: List<ResourceRecord> = listOf(),
) {
  val responseCode: Int
    get() = (flags and 0b0000_0000_0000_1111)

  // Avoid Short.hashCode(short) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + id
    result = 31 * result + flags
    result = 31 * result + questions.hashCode()
    result = 31 * result + answers.hashCode()
    result = 31 * result + authorityRecords.hashCode()
    result = 31 * result + additionalRecords.hashCode()
    return result
  }
}

internal data class Question(
  val name: String,
  val type: Short,
  val `class`: Short,
) {
  // Avoid Short.hashCode(short) which isn't available on Android 5.
  override fun hashCode(): Int {
    var result = 0
    result = 31 * result + name.hashCode()
    result = 31 * result + type
    result = 31 * result + `class`
    return result
  }
}

internal sealed interface ResourceRecord {
  val name: String
  val timeToLive: Int

  data class IpAddress(
    override val name: String,
    override val timeToLive: Int,
    val address: InetAddress,
  ) : ResourceRecord {
    // Avoid Int.hashCode(int) which isn't available on Android 5.
    override fun hashCode(): Int {
      var result = 0
      result = 31 * result + name.hashCode()
      result = 31 * result + timeToLive
      result = 31 * result + address.hashCode()
      return result
    }
  }

  data class Https(
    override val name: String,
    override val timeToLive: Int,
    val priority: Int,
    val targetName: String,
    val alpnIds: List<String>? = null,
    var port: Int = -1,
    val ipAddressHints: List<InetAddress> = listOf(),
    var echConfigList: ByteString? = null,
  ) : ResourceRecord {
    // Avoid Int.hashCode(int) which isn't available on Android 5.
    override fun hashCode(): Int {
      var result = 0
      result = 31 * result + name.hashCode()
      result = 31 * result + timeToLive
      result = 31 * result + priority
      result = 31 * result + targetName.hashCode()
      result = 31 * result + alpnIds.hashCode()
      result = 31 * result + port
      result = 31 * result + ipAddressHints.hashCode()
      result = 31 * result + echConfigList.hashCode()
      return result
    }
  }
}

/** https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4 */
internal const val TYPE_A = 1
internal const val TYPE_AAAA = 28
internal const val TYPE_HTTPS = 65

/** https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-2 */
internal const val CLASS_IN = 1

/** https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-6 */
internal const val RESPONSE_CODE_SUCCESS = 0
internal const val RESPONSE_CODE_SERVER_FAILURE = 2

/** https://www.iana.org/assignments/dns-svcb/dns-svcb.xhtml */
internal const val SERVICE_PARAMETER_MANDATORY = 0
internal const val SERVICE_PARAMETER_ALPN = 1
internal const val SERVICE_PARAMETER_NO_DEFAULT_ALPN = 2
internal const val SERVICE_PARAMETER_PORT = 3
internal const val SERVICE_PARAMETER_IPV4_HINT = 4
internal const val SERVICE_PARAMETER_ECH = 5
internal const val SERVICE_PARAMETER_IPV6_HINT = 6
