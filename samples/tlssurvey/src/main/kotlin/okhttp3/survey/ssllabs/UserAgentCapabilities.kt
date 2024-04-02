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
package okhttp3.survey.ssllabs

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
class UserAgentCapabilities(
  val abortsOnUnrecognizedName: Boolean,
  val alpnProtocols: List<String>,
  val ellipticCurves: List<Int>,
  val handshakeFormat: String,
  val hexHandshakeBytes: String,
  val highestProtocol: Int,
  val id: Int,
  val isGrade0: Boolean,
  val lowestProtocol: Int,
  val maxDhBits: Int,
  val maxRsaBits: Int,
  val minDhBits: Int,
  val minEcdsaBits: Int,
  val minRsaBits: Int,
  val name: String,
  val npnProtocols: List<String>,
  val platform: String?,
  val requiresSha2: Boolean,
  val signatureAlgorithms: List<Int>,
  val suiteIds: List<Int>,
  val suiteNames: List<String>,
  val supportsCompression: Boolean,
  val supportsNpn: Boolean,
  val supportsRi: Boolean,
  val supportsSni: Boolean,
  val supportsStapling: Boolean,
  val supportsTickets: Boolean,
  val userAgent: String?,
  val version: String,
)
