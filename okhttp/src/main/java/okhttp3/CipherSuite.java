/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.lang.Integer.MAX_VALUE;

/**
 * <a href="https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml">TLS cipher
 * suites</a>.
 *
 * <p><strong>Not all cipher suites are supported on all platforms.</strong> As newer cipher suites
 * are created (for stronger privacy, better performance, etc.) they will be adopted by the platform
 * and then exposed here. Cipher suites that are not available on either Android (through API level
 * 20) or Java (through JDK 8) are omitted for brevity.
 *
 * <p>See also <a href="https://android.googlesource.com/platform/external/conscrypt/+/master/src/main/java/org/conscrypt/NativeCrypto.java">NativeCrypto.java</a>
 * from conscrypt, which lists the cipher suites supported by Android.
 */
public final class CipherSuite {
  /**
   * Holds interned instances. This needs to be above the of() calls below so that it's
   * initialized by the time those parts of {@code <clinit>()} run.
   */
  private static final ConcurrentMap<String, CipherSuite> INSTANCES = new ConcurrentHashMap<>();

  // Last updated 2014-11-11 using cipher suites from Android 21 and Java 8.

  // public static final CipherSuite TLS_NULL_WITH_NULL_NULL = of("TLS_NULL_WITH_NULL_NULL", 0x0000, 5246, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_RSA_WITH_NULL_MD5 = of("SSL_RSA_WITH_NULL_MD5", 0x0001, 5246, 6, 10);
  public static final CipherSuite TLS_RSA_WITH_NULL_SHA = of("SSL_RSA_WITH_NULL_SHA", 0x0002, 5246, 6, 10);
  public static final CipherSuite TLS_RSA_EXPORT_WITH_RC4_40_MD5 = of("SSL_RSA_EXPORT_WITH_RC4_40_MD5", 0x0003, 4346, 6, 10);
  public static final CipherSuite TLS_RSA_WITH_RC4_128_MD5 = of("SSL_RSA_WITH_RC4_128_MD5", 0x0004, 5246, 6, 10);
  public static final CipherSuite TLS_RSA_WITH_RC4_128_SHA = of("SSL_RSA_WITH_RC4_128_SHA", 0x0005, 5246, 6, 10);
  // public static final CipherSuite TLS_RSA_EXPORT_WITH_RC2_CBC_40_MD5 = of("SSL_RSA_EXPORT_WITH_RC2_CBC_40_MD5", 0x0006, 4346, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_IDEA_CBC_SHA = of("TLS_RSA_WITH_IDEA_CBC_SHA", 0x0007, 5469, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_RSA_EXPORT_WITH_DES40_CBC_SHA = of("SSL_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x0008, 4346, 6, 10);
  public static final CipherSuite TLS_RSA_WITH_DES_CBC_SHA = of("SSL_RSA_WITH_DES_CBC_SHA", 0x0009, 5469, 6, 10);
  public static final CipherSuite TLS_RSA_WITH_3DES_EDE_CBC_SHA = of("SSL_RSA_WITH_3DES_EDE_CBC_SHA", 0x000a, 5246, 6, 10);
  // public static final CipherSuite TLS_DH_DSS_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DH_DSS_EXPORT_WITH_DES40_CBC_SHA", 0x000b, 4346, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_DES_CBC_SHA = of("TLS_DH_DSS_WITH_DES_CBC_SHA", 0x000c, 5469, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA = of("TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA", 0x000d, 5246, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DH_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x000e, 4346, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_DES_CBC_SHA = of("TLS_DH_RSA_WITH_DES_CBC_SHA", 0x000f, 5469, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA = of("TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA", 0x0010, 5246, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA", 0x0011, 4346, 6, 10);
  public static final CipherSuite TLS_DHE_DSS_WITH_DES_CBC_SHA = of("SSL_DHE_DSS_WITH_DES_CBC_SHA", 0x0012, 5469, 6, 10);
  public static final CipherSuite TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA = of("SSL_DHE_DSS_WITH_3DES_EDE_CBC_SHA", 0x0013, 5246, 6, 10);
  public static final CipherSuite TLS_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x0014, 4346, 6, 10);
  public static final CipherSuite TLS_DHE_RSA_WITH_DES_CBC_SHA = of("SSL_DHE_RSA_WITH_DES_CBC_SHA", 0x0015, 5469, 6, 10);
  public static final CipherSuite TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA = of("SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA", 0x0016, 5246, 6, 10);
  public static final CipherSuite TLS_DH_anon_EXPORT_WITH_RC4_40_MD5 = of("SSL_DH_anon_EXPORT_WITH_RC4_40_MD5", 0x0017, 4346, 6, 10);
  public static final CipherSuite TLS_DH_anon_WITH_RC4_128_MD5 = of("SSL_DH_anon_WITH_RC4_128_MD5", 0x0018, 5246, 6, 10);
  public static final CipherSuite TLS_DH_anon_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA", 0x0019, 4346, 6, 10);
  public static final CipherSuite TLS_DH_anon_WITH_DES_CBC_SHA = of("SSL_DH_anon_WITH_DES_CBC_SHA", 0x001a, 5469, 6, 10);
  public static final CipherSuite TLS_DH_anon_WITH_3DES_EDE_CBC_SHA = of("SSL_DH_anon_WITH_3DES_EDE_CBC_SHA", 0x001b, 5246, 6, 10);
  public static final CipherSuite TLS_KRB5_WITH_DES_CBC_SHA = of("TLS_KRB5_WITH_DES_CBC_SHA", 0x001e, 2712, 6, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_WITH_3DES_EDE_CBC_SHA = of("TLS_KRB5_WITH_3DES_EDE_CBC_SHA", 0x001f, 2712, 6, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_WITH_RC4_128_SHA = of("TLS_KRB5_WITH_RC4_128_SHA", 0x0020, 2712, 6, MAX_VALUE);
  // public static final CipherSuite TLS_KRB5_WITH_IDEA_CBC_SHA = of("TLS_KRB5_WITH_IDEA_CBC_SHA", 0x0021, 2712, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_WITH_DES_CBC_MD5 = of("TLS_KRB5_WITH_DES_CBC_MD5", 0x0022, 2712, 6, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_WITH_3DES_EDE_CBC_MD5 = of("TLS_KRB5_WITH_3DES_EDE_CBC_MD5", 0x0023, 2712, 6, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_WITH_RC4_128_MD5 = of("TLS_KRB5_WITH_RC4_128_MD5", 0x0024, 2712, 6, MAX_VALUE);
  // public static final CipherSuite TLS_KRB5_WITH_IDEA_CBC_MD5 = of("TLS_KRB5_WITH_IDEA_CBC_MD5", 0x0025, 2712, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA = of("TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA", 0x0026, 2712, 6, MAX_VALUE);
  // public static final CipherSuite TLS_KRB5_EXPORT_WITH_RC2_CBC_40_SHA = of("TLS_KRB5_EXPORT_WITH_RC2_CBC_40_SHA", 0x0027, 2712, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_EXPORT_WITH_RC4_40_SHA = of("TLS_KRB5_EXPORT_WITH_RC4_40_SHA", 0x0028, 2712, 6, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5 = of("TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5", 0x0029, 2712, 6, MAX_VALUE);
  // public static final CipherSuite TLS_KRB5_EXPORT_WITH_RC2_CBC_40_MD5 = of("TLS_KRB5_EXPORT_WITH_RC2_CBC_40_MD5", 0x002a, 2712, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_KRB5_EXPORT_WITH_RC4_40_MD5 = of("TLS_KRB5_EXPORT_WITH_RC4_40_MD5", 0x002b, 2712, 6, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_NULL_SHA = of("TLS_PSK_WITH_NULL_SHA", 0x002c, 4785, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_NULL_SHA = of("TLS_DHE_PSK_WITH_NULL_SHA", 0x002d, 4785, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_NULL_SHA = of("TLS_RSA_PSK_WITH_NULL_SHA", 0x002e, 4785, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_RSA_WITH_AES_128_CBC_SHA = of("TLS_RSA_WITH_AES_128_CBC_SHA", 0x002f, 5246, 6, 10);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_128_CBC_SHA = of("TLS_DH_DSS_WITH_AES_128_CBC_SHA", 0x0030, 5246, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_128_CBC_SHA = of("TLS_DH_RSA_WITH_AES_128_CBC_SHA", 0x0031, 5246, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_128_CBC_SHA = of("TLS_DHE_DSS_WITH_AES_128_CBC_SHA", 0x0032, 5246, 6, 10);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_CBC_SHA = of("TLS_DHE_RSA_WITH_AES_128_CBC_SHA", 0x0033, 5246, 6, 10);
  public static final CipherSuite TLS_DH_anon_WITH_AES_128_CBC_SHA = of("TLS_DH_anon_WITH_AES_128_CBC_SHA", 0x0034, 5246, 6, 10);
  public static final CipherSuite TLS_RSA_WITH_AES_256_CBC_SHA = of("TLS_RSA_WITH_AES_256_CBC_SHA", 0x0035, 5246, 6, 10);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_256_CBC_SHA = of("TLS_DH_DSS_WITH_AES_256_CBC_SHA", 0x0036, 5246, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_256_CBC_SHA = of("TLS_DH_RSA_WITH_AES_256_CBC_SHA", 0x0037, 5246, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_256_CBC_SHA = of("TLS_DHE_DSS_WITH_AES_256_CBC_SHA", 0x0038, 5246, 6, 10);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_CBC_SHA = of("TLS_DHE_RSA_WITH_AES_256_CBC_SHA", 0x0039, 5246, 6, 10);
  public static final CipherSuite TLS_DH_anon_WITH_AES_256_CBC_SHA = of("TLS_DH_anon_WITH_AES_256_CBC_SHA", 0x003a, 5246, 6, 10);
  public static final CipherSuite TLS_RSA_WITH_NULL_SHA256 = of("TLS_RSA_WITH_NULL_SHA256", 0x003b, 5246, 7, 21);
  public static final CipherSuite TLS_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_RSA_WITH_AES_128_CBC_SHA256", 0x003c, 5246, 7, 21);
  public static final CipherSuite TLS_RSA_WITH_AES_256_CBC_SHA256 = of("TLS_RSA_WITH_AES_256_CBC_SHA256", 0x003d, 5246, 7, 21);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_128_CBC_SHA256 = of("TLS_DH_DSS_WITH_AES_128_CBC_SHA256", 0x003e, 5246, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_DH_RSA_WITH_AES_128_CBC_SHA256", 0x003f, 5246, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_128_CBC_SHA256 = of("TLS_DHE_DSS_WITH_AES_128_CBC_SHA256", 0x0040, 5246, 7, 21);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_128_CBC_SHA = of("TLS_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0041, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA", 0x0042, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0043, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA", 0x0044, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0045, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA", 0x0046, 5932, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", 0x0067, 5246, 7, 21);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_256_CBC_SHA256 = of("TLS_DH_DSS_WITH_AES_256_CBC_SHA256", 0x0068, 5246, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_256_CBC_SHA256 = of("TLS_DH_RSA_WITH_AES_256_CBC_SHA256", 0x0069, 5246, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_256_CBC_SHA256 = of("TLS_DHE_DSS_WITH_AES_256_CBC_SHA256", 0x006a, 5246, 7, 21);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_CBC_SHA256 = of("TLS_DHE_RSA_WITH_AES_256_CBC_SHA256", 0x006b, 5246, 7, 21);
  public static final CipherSuite TLS_DH_anon_WITH_AES_128_CBC_SHA256 = of("TLS_DH_anon_WITH_AES_128_CBC_SHA256", 0x006c, 5246, 7, 21);
  public static final CipherSuite TLS_DH_anon_WITH_AES_256_CBC_SHA256 = of("TLS_DH_anon_WITH_AES_256_CBC_SHA256", 0x006d, 5246, 7, 21);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_256_CBC_SHA = of("TLS_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0084, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA", 0x0085, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0086, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA", 0x0087, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0088, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA", 0x0089, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_RC4_128_SHA = of("TLS_PSK_WITH_RC4_128_SHA", 0x008a, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_3DES_EDE_CBC_SHA = of("TLS_PSK_WITH_3DES_EDE_CBC_SHA", 0x008b, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_CBC_SHA = of("TLS_PSK_WITH_AES_128_CBC_SHA", 0x008c, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_CBC_SHA = of("TLS_PSK_WITH_AES_256_CBC_SHA", 0x008d, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_RC4_128_SHA = of("TLS_DHE_PSK_WITH_RC4_128_SHA", 0x008e, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA = of("TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA", 0x008f, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_128_CBC_SHA = of("TLS_DHE_PSK_WITH_AES_128_CBC_SHA", 0x0090, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_256_CBC_SHA = of("TLS_DHE_PSK_WITH_AES_256_CBC_SHA", 0x0091, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_RC4_128_SHA = of("TLS_RSA_PSK_WITH_RC4_128_SHA", 0x0092, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA = of("TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA", 0x0093, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_128_CBC_SHA = of("TLS_RSA_PSK_WITH_AES_128_CBC_SHA", 0x0094, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_256_CBC_SHA = of("TLS_RSA_PSK_WITH_AES_256_CBC_SHA", 0x0095, 4279, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_SEED_CBC_SHA = of("TLS_RSA_WITH_SEED_CBC_SHA", 0x0096, 4162, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_SEED_CBC_SHA = of("TLS_DH_DSS_WITH_SEED_CBC_SHA", 0x0097, 4162, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_SEED_CBC_SHA = of("TLS_DH_RSA_WITH_SEED_CBC_SHA", 0x0098, 4162, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_SEED_CBC_SHA = of("TLS_DHE_DSS_WITH_SEED_CBC_SHA", 0x0099, 4162, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_SEED_CBC_SHA = of("TLS_DHE_RSA_WITH_SEED_CBC_SHA", 0x009a, 4162, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_SEED_CBC_SHA = of("TLS_DH_anon_WITH_SEED_CBC_SHA", 0x009b, 4162, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_RSA_WITH_AES_128_GCM_SHA256", 0x009c, 5288, 8, 21);
  public static final CipherSuite TLS_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_RSA_WITH_AES_256_GCM_SHA384", 0x009d, 5288, 8, 21);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", 0x009e, 5288, 8, 21);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", 0x009f, 5288, 8, 21);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_DH_RSA_WITH_AES_128_GCM_SHA256", 0x00a0, 5288, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_DH_RSA_WITH_AES_256_GCM_SHA384", 0x00a1, 5288, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_128_GCM_SHA256 = of("TLS_DHE_DSS_WITH_AES_128_GCM_SHA256", 0x00a2, 5288, 8, 21);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_256_GCM_SHA384 = of("TLS_DHE_DSS_WITH_AES_256_GCM_SHA384", 0x00a3, 5288, 8, 21);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_128_GCM_SHA256 = of("TLS_DH_DSS_WITH_AES_128_GCM_SHA256", 0x00a4, 5288, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_256_GCM_SHA384 = of("TLS_DH_DSS_WITH_AES_256_GCM_SHA384", 0x00a5, 5288, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_DH_anon_WITH_AES_128_GCM_SHA256 = of("TLS_DH_anon_WITH_AES_128_GCM_SHA256", 0x00a6, 5288, 8, 21);
  public static final CipherSuite TLS_DH_anon_WITH_AES_256_GCM_SHA384 = of("TLS_DH_anon_WITH_AES_256_GCM_SHA384", 0x00a7, 5288, 8, 21);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_GCM_SHA256 = of("TLS_PSK_WITH_AES_128_GCM_SHA256", 0x00a8, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_GCM_SHA384 = of("TLS_PSK_WITH_AES_256_GCM_SHA384", 0x00a9, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_128_GCM_SHA256 = of("TLS_DHE_PSK_WITH_AES_128_GCM_SHA256", 0x00aa, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_256_GCM_SHA384 = of("TLS_DHE_PSK_WITH_AES_256_GCM_SHA384", 0x00ab, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_128_GCM_SHA256 = of("TLS_RSA_PSK_WITH_AES_128_GCM_SHA256", 0x00ac, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_256_GCM_SHA384 = of("TLS_RSA_PSK_WITH_AES_256_GCM_SHA384", 0x00ad, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_CBC_SHA256 = of("TLS_PSK_WITH_AES_128_CBC_SHA256", 0x00ae, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_CBC_SHA384 = of("TLS_PSK_WITH_AES_256_CBC_SHA384", 0x00af, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_NULL_SHA256 = of("TLS_PSK_WITH_NULL_SHA256", 0x00b0, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_NULL_SHA384 = of("TLS_PSK_WITH_NULL_SHA384", 0x00b1, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_128_CBC_SHA256 = of("TLS_DHE_PSK_WITH_AES_128_CBC_SHA256", 0x00b2, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_256_CBC_SHA384 = of("TLS_DHE_PSK_WITH_AES_256_CBC_SHA384", 0x00b3, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_NULL_SHA256 = of("TLS_DHE_PSK_WITH_NULL_SHA256", 0x00b4, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_NULL_SHA384 = of("TLS_DHE_PSK_WITH_NULL_SHA384", 0x00b5, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_128_CBC_SHA256 = of("TLS_RSA_PSK_WITH_AES_128_CBC_SHA256", 0x00b6, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_256_CBC_SHA384 = of("TLS_RSA_PSK_WITH_AES_256_CBC_SHA384", 0x00b7, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_NULL_SHA256 = of("TLS_RSA_PSK_WITH_NULL_SHA256", 0x00b8, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_NULL_SHA384 = of("TLS_RSA_PSK_WITH_NULL_SHA384", 0x00b9, 5487, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00ba, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256", 0x00bb, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00bc, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256", 0x00bd, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00be, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256", 0x00bf, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c0, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256", 0x00c1, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c2, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256", 0x00c3, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c4, 5932, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256", 0x00c5, 5932, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_EMPTY_RENEGOTIATION_INFO_SCSV = of("TLS_EMPTY_RENEGOTIATION_INFO_SCSV", 0x00ff, 5746, 6, 14);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_NULL_SHA = of("TLS_ECDH_ECDSA_WITH_NULL_SHA", 0xc001, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_RC4_128_SHA = of("TLS_ECDH_ECDSA_WITH_RC4_128_SHA", 0xc002, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA", 0xc003, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA = of("TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA", 0xc004, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA = of("TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA", 0xc005, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_NULL_SHA = of("TLS_ECDHE_ECDSA_WITH_NULL_SHA", 0xc006, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_RC4_128_SHA = of("TLS_ECDHE_ECDSA_WITH_RC4_128_SHA", 0xc007, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA", 0xc008, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA = of("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", 0xc009, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA = of("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", 0xc00a, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_RSA_WITH_NULL_SHA = of("TLS_ECDH_RSA_WITH_NULL_SHA", 0xc00b, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_RSA_WITH_RC4_128_SHA = of("TLS_ECDH_RSA_WITH_RC4_128_SHA", 0xc00c, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA", 0xc00d, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_128_CBC_SHA = of("TLS_ECDH_RSA_WITH_AES_128_CBC_SHA", 0xc00e, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_256_CBC_SHA = of("TLS_ECDH_RSA_WITH_AES_256_CBC_SHA", 0xc00f, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_NULL_SHA = of("TLS_ECDHE_RSA_WITH_NULL_SHA", 0xc010, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_RC4_128_SHA = of("TLS_ECDHE_RSA_WITH_RC4_128_SHA", 0xc011, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA", 0xc012, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA = of("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", 0xc013, 4492, 7, 14);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA = of("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", 0xc014, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_anon_WITH_NULL_SHA = of("TLS_ECDH_anon_WITH_NULL_SHA", 0xc015, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_anon_WITH_RC4_128_SHA = of("TLS_ECDH_anon_WITH_RC4_128_SHA", 0xc016, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA", 0xc017, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_anon_WITH_AES_128_CBC_SHA = of("TLS_ECDH_anon_WITH_AES_128_CBC_SHA", 0xc018, 4492, 7, 14);
  public static final CipherSuite TLS_ECDH_anon_WITH_AES_256_CBC_SHA = of("TLS_ECDH_anon_WITH_AES_256_CBC_SHA", 0xc019, 4492, 7, 14);
  // public static final CipherSuite TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA = of("TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA", 0xc01a, 5054, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA = of("TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA", 0xc01b, 5054, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA = of("TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA", 0xc01c, 5054, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_SRP_SHA_WITH_AES_128_CBC_SHA = of("TLS_SRP_SHA_WITH_AES_128_CBC_SHA", 0xc01d, 5054, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA = of("TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA", 0xc01e, 5054, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA = of("TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA", 0xc01f, 5054, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_SRP_SHA_WITH_AES_256_CBC_SHA = of("TLS_SRP_SHA_WITH_AES_256_CBC_SHA", 0xc020, 5054, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA = of("TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA", 0xc021, 5054, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA = of("TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA", 0xc022, 5054, MAX_VALUE, MAX_VALUE);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256 = of("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", 0xc023, 5289, 7, 21);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384 = of("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", 0xc024, 5289, 7, 21);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256 = of("TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256", 0xc025, 5289, 7, 21);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384 = of("TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384", 0xc026, 5289, 7, 21);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", 0xc027, 5289, 7, 21);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384 = of("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", 0xc028, 5289, 7, 21);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256", 0xc029, 5289, 7, 21);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384 = of("TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384", 0xc02a, 5289, 7, 21);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 = of("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", 0xc02b, 5289, 8, 21);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 = of("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", 0xc02c, 5289, 8, 21);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256 = of("TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256", 0xc02d, 5289, 8, 21);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384 = of("TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384", 0xc02e, 5289, 8, 21);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", 0xc02f, 5289, 8, 21);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", 0xc030, 5289, 8, 21);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256", 0xc031, 5289, 8, 21);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384", 0xc032, 5289, 8, 21);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_RC4_128_SHA = of("TLS_ECDHE_PSK_WITH_RC4_128_SHA", 0xc033, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA", 0xc034, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA = of("TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA", 0xc035, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA = of("TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA", 0xc036, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256 = of("TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256", 0xc037, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384 = of("TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384", 0xc038, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_NULL_SHA = of("TLS_ECDHE_PSK_WITH_NULL_SHA", 0xc039, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_NULL_SHA256 = of("TLS_ECDHE_PSK_WITH_NULL_SHA256", 0xc03a, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_NULL_SHA384 = of("TLS_ECDHE_PSK_WITH_NULL_SHA384", 0xc03b, 5489, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_RSA_WITH_ARIA_128_CBC_SHA256", 0xc03c, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_RSA_WITH_ARIA_256_CBC_SHA384", 0xc03d, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256 = of("TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256", 0xc03e, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384 = of("TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384", 0xc03f, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256", 0xc040, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384", 0xc041, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256 = of("TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256", 0xc042, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384 = of("TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384", 0xc043, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256", 0xc044, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384", 0xc045, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_ARIA_128_CBC_SHA256 = of("TLS_DH_anon_WITH_ARIA_128_CBC_SHA256", 0xc046, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_ARIA_256_CBC_SHA384 = of("TLS_DH_anon_WITH_ARIA_256_CBC_SHA384", 0xc047, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256", 0xc048, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384", 0xc049, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256", 0xc04a, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384", 0xc04b, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256", 0xc04c, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384", 0xc04d, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256", 0xc04e, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384", 0xc04f, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_RSA_WITH_ARIA_128_GCM_SHA256", 0xc050, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_RSA_WITH_ARIA_256_GCM_SHA384", 0xc051, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_DHE_RSA_WITH_ARIA_128_GCM_SHA256", 0xc052, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_DHE_RSA_WITH_ARIA_256_GCM_SHA384", 0xc053, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256", 0xc054, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384", 0xc055, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_ARIA_128_GCM_SHA256 = of("TLS_DHE_DSS_WITH_ARIA_128_GCM_SHA256", 0xc056, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_ARIA_256_GCM_SHA384 = of("TLS_DHE_DSS_WITH_ARIA_256_GCM_SHA384", 0xc057, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256 = of("TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256", 0xc058, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384 = of("TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384", 0xc059, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_ARIA_128_GCM_SHA256 = of("TLS_DH_anon_WITH_ARIA_128_GCM_SHA256", 0xc05a, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_ARIA_256_GCM_SHA384 = of("TLS_DH_anon_WITH_ARIA_256_GCM_SHA384", 0xc05b, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_ECDHE_ECDSA_WITH_ARIA_128_GCM_SHA256", 0xc05c, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_ECDHE_ECDSA_WITH_ARIA_256_GCM_SHA384", 0xc05d, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256", 0xc05e, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384", 0xc05f, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_ECDHE_RSA_WITH_ARIA_128_GCM_SHA256", 0xc060, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_ECDHE_RSA_WITH_ARIA_256_GCM_SHA384", 0xc061, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256", 0xc062, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384", 0xc063, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_ARIA_128_CBC_SHA256 = of("TLS_PSK_WITH_ARIA_128_CBC_SHA256", 0xc064, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_ARIA_256_CBC_SHA384 = of("TLS_PSK_WITH_ARIA_256_CBC_SHA384", 0xc065, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256 = of("TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256", 0xc066, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384 = of("TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384", 0xc067, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256 = of("TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256", 0xc068, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384 = of("TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384", 0xc069, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_ARIA_128_GCM_SHA256 = of("TLS_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06a, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_ARIA_256_GCM_SHA384 = of("TLS_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06b, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_ARIA_128_GCM_SHA256 = of("TLS_DHE_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06c, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_ARIA_256_GCM_SHA384 = of("TLS_DHE_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06d, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256 = of("TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06e, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384 = of("TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06f, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256", 0xc070, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384", 0xc071, 6209, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc072, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc073, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc074, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc075, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc076, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc077, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc078, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc079, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07a, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07b, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DHE_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07c, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DHE_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07d, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07e, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07f, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DHE_DSS_WITH_CAMELLIA_128_GCM_SHA256", 0xc080, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DHE_DSS_WITH_CAMELLIA_256_GCM_SHA384", 0xc081, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256", 0xc082, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384", 0xc083, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256", 0xc084, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384", 0xc085, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc086, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc087, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc088, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc089, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_ECDHE_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc08a, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_ECDHE_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc08b, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc08c, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc08d, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc08e, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc08f, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DHE_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc090, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DHE_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc091, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc092, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc093, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc094, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc095, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc096, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc097, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc098, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc099, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc09a, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc09b, 6367, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_AES_128_CCM = of("TLS_RSA_WITH_AES_128_CCM", 0xc09c, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_AES_256_CCM = of("TLS_RSA_WITH_AES_256_CCM", 0xc09d, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_CCM = of("TLS_DHE_RSA_WITH_AES_128_CCM", 0xc09e, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_CCM = of("TLS_DHE_RSA_WITH_AES_256_CCM", 0xc09f, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_AES_128_CCM_8 = of("TLS_RSA_WITH_AES_128_CCM_8", 0xc0a0, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_RSA_WITH_AES_256_CCM_8 = of("TLS_RSA_WITH_AES_256_CCM_8", 0xc0a1, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_CCM_8 = of("TLS_DHE_RSA_WITH_AES_128_CCM_8", 0xc0a2, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_CCM_8 = of("TLS_DHE_RSA_WITH_AES_256_CCM_8", 0xc0a3, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_CCM = of("TLS_PSK_WITH_AES_128_CCM", 0xc0a4, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_CCM = of("TLS_PSK_WITH_AES_256_CCM", 0xc0a5, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_128_CCM = of("TLS_DHE_PSK_WITH_AES_128_CCM", 0xc0a6, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_256_CCM = of("TLS_DHE_PSK_WITH_AES_256_CCM", 0xc0a7, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_CCM_8 = of("TLS_PSK_WITH_AES_128_CCM_8", 0xc0a8, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_CCM_8 = of("TLS_PSK_WITH_AES_256_CCM_8", 0xc0a9, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_DHE_WITH_AES_128_CCM_8 = of("TLS_PSK_DHE_WITH_AES_128_CCM_8", 0xc0aa, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_PSK_DHE_WITH_AES_256_CCM_8 = of("TLS_PSK_DHE_WITH_AES_256_CCM_8", 0xc0ab, 6655, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_CCM = of("TLS_ECDHE_ECDSA_WITH_AES_128_CCM", 0xc0ac, 7251, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_CCM = of("TLS_ECDHE_ECDSA_WITH_AES_256_CCM", 0xc0ad, 7251, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8 = of("TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8", 0xc0ae, 7251, MAX_VALUE, MAX_VALUE);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8 = of("TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8", 0xc0af, 7251, MAX_VALUE, MAX_VALUE);
  ;

  final String javaName;

  /**
   * @param javaName the name used by Java APIs for this cipher suite. Different than the IANA name
   * for older cipher suites because the prefix is {@code SSL_} instead of {@code TLS_}.
   */
  public static CipherSuite forJavaName(String javaName) {
    CipherSuite result = INSTANCES.get(javaName);
    if (result == null) {
      CipherSuite sample = new CipherSuite(javaName);
      CipherSuite canonical = INSTANCES.putIfAbsent(javaName, sample);
      result = (canonical == null) ? sample : canonical;
    }
    return result;
  }

  private CipherSuite(String javaName) {
    if (javaName == null) {
      throw new NullPointerException();
    }
    this.javaName = javaName;
  }

  /**
   * @param javaName the name used by Java APIs for this cipher suite. Different than the IANA name
   * for older cipher suites because the prefix is {@code SSL_} instead of {@code TLS_}.
   * @param value the integer identifier for this cipher suite. (Documentation only.)
   * @param rfc the RFC describing this cipher suite. (Documentation only.)
   * @param sinceJavaVersion the first major Java release supporting this cipher suite.
   * @param sinceAndroidVersion the first Android SDK version supporting this cipher suite.
   */
  private static CipherSuite of(
          String javaName, int value, int rfc, int sinceJavaVersion, int sinceAndroidVersion) {
    return forJavaName(javaName);
  }

  /**
   * Returns the Java name of this cipher suite. For some older cipher suites the Java name has the
   * prefix {@code SSL_}, causing the Java name to be different from the instance name which is
   * always prefixed {@code TLS_}. For example, {@code TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName()}
   * is {@code "SSL_RSA_EXPORT_WITH_RC4_40_MD5"}.
   */
  public String javaName() {
    return javaName;
  }

  @Override
  public String toString() {
    return javaName;
  }
}
