/*
 * Copyright (C) 2021 Square, Inc.
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
package okhttp3

/**
 * Versions of TLS that can be offered when negotiating a secure socket.
 */
expect enum class TlsVersion {
  TLS_1_3, // 2016.
  TLS_1_2, // 2008.
  TLS_1_1, // 2006.
  TLS_1_0, // 1999.
  SSL_3_0; // 1996.
}
