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
package okhttp3.tls.internal.der

/** ASN.1 object identifiers used internally by this implementation. */
internal object ObjectIdentifiers {
  const val ecPublicKey = "1.2.840.10045.2.1"
  const val sha256withEcdsa = "1.2.840.10045.4.3.2"
  const val rsaEncryption = "1.2.840.113549.1.1.1"
  const val sha256WithRSAEncryption = "1.2.840.113549.1.1.11"
  const val subjectAlternativeName = "2.5.29.17"
  const val basicConstraints = "2.5.29.19"
  const val commonName = "2.5.4.3"
  const val organizationalUnitName = "2.5.4.11"
}
