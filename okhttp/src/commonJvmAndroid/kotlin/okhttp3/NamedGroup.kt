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
 * **Not all named groups are supported on all platforms,** and availability depends on the
 * underlying TLS provider:
 *
 *  * **JDK:** the [javax.net.ssl.SSLParameters.setNamedGroups] API is available on Java 20+. The
 *    hybrid groups below ([X25519MLKEM768], [SECP256R1MLKEM768], [SECP384R1MLKEM1024]) are
 *    implemented natively starting with JDK 27 (JEP 527), or earlier via Conscrypt / Bouncy Castle.
 *  * **Android:** Conscrypt gained the `setNamedGroups` API and the hybrid groups in recent
 *    releases; older devices silently ignore unsupported groups.
 *
 * The constants' [javaName] values match the standard algorithm names accepted by the JDK system
 * property `jdk.tls.namedGroups` and by [javax.net.ssl.SSLParameters.setNamedGroups]. As with
 * [CipherSuite], [forJavaName] accepts arbitrary names so callers may request groups that are not
 * yet enumerated here.
 */
class NamedGroup private constructor(
  /** Returns the name of this named group as used by Java APIs and `jdk.tls.namedGroups`. */
  @get:JvmName("javaName") val javaName: String,
) {
  override fun equals(other: Any?): Boolean = other is NamedGroup && other.javaName == javaName

  override fun hashCode(): Int = javaName.hashCode()

  override fun toString(): String = javaName

  companion object {
    // Post-quantum hybrid key exchange (ML-KEM + classical ECDHE), RFC 9794.
    @JvmField val X25519MLKEM768 = NamedGroup("X25519MLKEM768")

    @JvmField val SECP256R1MLKEM768 = NamedGroup("SecP256r1MLKEM768")

    @JvmField val SECP384R1MLKEM1024 = NamedGroup("SecP384r1MLKEM1024")

    // Classical elliptic-curve groups.
    @JvmField val X25519 = NamedGroup("x25519")

    @JvmField val X448 = NamedGroup("x448")

    @JvmField val SECP256R1 = NamedGroup("secp256r1")

    @JvmField val SECP384R1 = NamedGroup("secp384r1")

    @JvmField val SECP521R1 = NamedGroup("secp521r1")

    // Finite-field Diffie-Hellman groups, RFC 7919.
    @JvmField val FFDHE2048 = NamedGroup("ffdhe2048")

    @JvmField val FFDHE3072 = NamedGroup("ffdhe3072")

    @JvmField val FFDHE4096 = NamedGroup("ffdhe4096")

    @JvmField val FFDHE6144 = NamedGroup("ffdhe6144")

    @JvmField val FFDHE8192 = NamedGroup("ffdhe8192")

    /**
     * Returns the named group for [javaName]. Names that aren't enumerated here are supported too;
     * two instances with the same [javaName] are equal.
     */
    @JvmStatic
    fun forJavaName(javaName: String): NamedGroup = NamedGroup(javaName)
  }
}
