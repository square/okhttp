/*
 * Copyright (C) 2026 Square, Inc.
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
 * Named groups (formerly "supported groups" / "elliptic curves") offered in the TLS 1.3
 * `supported_groups` extension. These select the key exchange algorithm used to establish the shared
 * secret, including the post-quantum hybrid schemes standardized in
 * [RFC 9794](https://www.rfc-editor.org/rfc/rfc9794.html).
 *
 * Named groups are configured separately from [cipher suites][CipherSuite]: in TLS 1.3 the key
 * exchange algorithm is negotiated via the `supported_groups` extension, not via the cipher suite.
 * To require or prefer post-quantum key exchange, set the desired groups on a [ConnectionSpec] with
 * [ConnectionSpec.Builder.namedGroups].
 *
 * Whether a given group can actually be negotiated depends on the underlying TLS provider:
 *
 *  * **JDK:** the [javax.net.ssl.SSLParameters.setNamedGroups] API is available on Java 20+. The
 *    hybrid groups below ([X25519MLKEM768], [SECP256R1MLKEM768], [SECP384R1MLKEM1024]) are
 *    implemented natively starting with JDK 27 (JEP 527), or earlier via Conscrypt / Bouncy Castle.
 *  * **Android:** Conscrypt gained the `setNamedGroups` API and the hybrid groups in recent
 *    releases; older devices silently ignore unsupported groups.
 *
 * The string constants match the standard algorithm names accepted by the JDK system property
 * `jdk.tls.namedGroups` and by [javax.net.ssl.SSLParameters.setNamedGroups]. As with cipher suites
 * and TLS versions, [ConnectionSpec.Builder.namedGroups] also accepts raw strings so that callers
 * may request groups that are not yet enumerated here.
 */
enum class NamedGroup(
  @get:JvmName("javaName") val javaName: String,
) {
  // Post-quantum hybrid key exchange (ML-KEM + classical ECDHE), RFC 9794.
  X25519MLKEM768("X25519MLKEM768"),
  SECP256R1MLKEM768("SecP256r1MLKEM768"),
  SECP384R1MLKEM1024("SecP384r1MLKEM1024"),

  // Classical elliptic-curve groups.
  X25519("x25519"),
  X448("x448"),
  SECP256R1("secp256r1"),
  SECP384R1("secp384r1"),
  SECP521R1("secp521r1"),

  // Finite-field Diffie-Hellman groups, RFC 7919.
  FFDHE2048("ffdhe2048"),
  FFDHE3072("ffdhe3072"),
  FFDHE4096("ffdhe4096"),
  FFDHE6144("ffdhe6144"),
  FFDHE8192("ffdhe8192"),
  ;

  companion object {
    /**
     * Returns the [NamedGroup] for [javaName], or throws if it is not a known group. Use the string
     * form of [ConnectionSpec.Builder.namedGroups] to configure groups that aren't enumerated here.
     */
    @JvmStatic
    fun forJavaName(javaName: String): NamedGroup =
      entries.firstOrNull { it.javaName == javaName }
        ?: throw IllegalArgumentException("Unexpected named group: $javaName")
  }
}
