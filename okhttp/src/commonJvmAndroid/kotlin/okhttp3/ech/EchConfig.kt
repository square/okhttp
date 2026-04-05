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
package okhttp3.ech

import okio.ByteString

/**
 * Configuration for Encrypted Client Hello (ECH).
 *
 * This class contains the parameters required for a client to encrypt its ClientHello message,
 * protecting sensitive fields such as the Server Name Indication (SNI) from passive observers.
 * These parameters are typically retrieved from DNS via HTTPS or SVCB records.
 */
data class EchConfig(
  val config: ByteString,
)
