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

  // Last updated 2016-07-03 using cipher suites from Android 24 and Java 9.

  // public static final CipherSuite TLS_NULL_WITH_NULL_NULL = of("TLS_NULL_WITH_NULL_NULL", 0x0000);
  public static final CipherSuite TLS_RSA_WITH_NULL_MD5 = of("SSL_RSA_WITH_NULL_MD5", 0x0001);
  public static final CipherSuite TLS_RSA_WITH_NULL_SHA = of("SSL_RSA_WITH_NULL_SHA", 0x0002);
  public static final CipherSuite TLS_RSA_EXPORT_WITH_RC4_40_MD5 = of("SSL_RSA_EXPORT_WITH_RC4_40_MD5", 0x0003);
  public static final CipherSuite TLS_RSA_WITH_RC4_128_MD5 = of("SSL_RSA_WITH_RC4_128_MD5", 0x0004);
  public static final CipherSuite TLS_RSA_WITH_RC4_128_SHA = of("SSL_RSA_WITH_RC4_128_SHA", 0x0005);
  // public static final CipherSuite TLS_RSA_EXPORT_WITH_RC2_CBC_40_MD5 = of("SSL_RSA_EXPORT_WITH_RC2_CBC_40_MD5", 0x0006);
  // public static final CipherSuite TLS_RSA_WITH_IDEA_CBC_SHA = of("TLS_RSA_WITH_IDEA_CBC_SHA", 0x0007);
  public static final CipherSuite TLS_RSA_EXPORT_WITH_DES40_CBC_SHA = of("SSL_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x0008);
  public static final CipherSuite TLS_RSA_WITH_DES_CBC_SHA = of("SSL_RSA_WITH_DES_CBC_SHA", 0x0009);
  public static final CipherSuite TLS_RSA_WITH_3DES_EDE_CBC_SHA = of("SSL_RSA_WITH_3DES_EDE_CBC_SHA", 0x000a);
  // public static final CipherSuite TLS_DH_DSS_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DH_DSS_EXPORT_WITH_DES40_CBC_SHA", 0x000b);
  // public static final CipherSuite TLS_DH_DSS_WITH_DES_CBC_SHA = of("TLS_DH_DSS_WITH_DES_CBC_SHA", 0x000c);
  // public static final CipherSuite TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA = of("TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA", 0x000d);
  // public static final CipherSuite TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DH_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x000e);
  // public static final CipherSuite TLS_DH_RSA_WITH_DES_CBC_SHA = of("TLS_DH_RSA_WITH_DES_CBC_SHA", 0x000f);
  // public static final CipherSuite TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA = of("TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA", 0x0010);
  public static final CipherSuite TLS_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA", 0x0011);
  public static final CipherSuite TLS_DHE_DSS_WITH_DES_CBC_SHA = of("SSL_DHE_DSS_WITH_DES_CBC_SHA", 0x0012);
  public static final CipherSuite TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA = of("SSL_DHE_DSS_WITH_3DES_EDE_CBC_SHA", 0x0013);
  public static final CipherSuite TLS_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA", 0x0014);
  public static final CipherSuite TLS_DHE_RSA_WITH_DES_CBC_SHA = of("SSL_DHE_RSA_WITH_DES_CBC_SHA", 0x0015);
  public static final CipherSuite TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA = of("SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA", 0x0016);
  public static final CipherSuite TLS_DH_anon_EXPORT_WITH_RC4_40_MD5 = of("SSL_DH_anon_EXPORT_WITH_RC4_40_MD5", 0x0017);
  public static final CipherSuite TLS_DH_anon_WITH_RC4_128_MD5 = of("SSL_DH_anon_WITH_RC4_128_MD5", 0x0018);
  public static final CipherSuite TLS_DH_anon_EXPORT_WITH_DES40_CBC_SHA = of("SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA", 0x0019);
  public static final CipherSuite TLS_DH_anon_WITH_DES_CBC_SHA = of("SSL_DH_anon_WITH_DES_CBC_SHA", 0x001a);
  public static final CipherSuite TLS_DH_anon_WITH_3DES_EDE_CBC_SHA = of("SSL_DH_anon_WITH_3DES_EDE_CBC_SHA", 0x001b);
  public static final CipherSuite TLS_KRB5_WITH_DES_CBC_SHA = of("TLS_KRB5_WITH_DES_CBC_SHA", 0x001e);
  public static final CipherSuite TLS_KRB5_WITH_3DES_EDE_CBC_SHA = of("TLS_KRB5_WITH_3DES_EDE_CBC_SHA", 0x001f);
  public static final CipherSuite TLS_KRB5_WITH_RC4_128_SHA = of("TLS_KRB5_WITH_RC4_128_SHA", 0x0020);
  // public static final CipherSuite TLS_KRB5_WITH_IDEA_CBC_SHA = of("TLS_KRB5_WITH_IDEA_CBC_SHA", 0x0021);
  public static final CipherSuite TLS_KRB5_WITH_DES_CBC_MD5 = of("TLS_KRB5_WITH_DES_CBC_MD5", 0x0022);
  public static final CipherSuite TLS_KRB5_WITH_3DES_EDE_CBC_MD5 = of("TLS_KRB5_WITH_3DES_EDE_CBC_MD5", 0x0023);
  public static final CipherSuite TLS_KRB5_WITH_RC4_128_MD5 = of("TLS_KRB5_WITH_RC4_128_MD5", 0x0024);
  // public static final CipherSuite TLS_KRB5_WITH_IDEA_CBC_MD5 = of("TLS_KRB5_WITH_IDEA_CBC_MD5", 0x0025);
  public static final CipherSuite TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA = of("TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA", 0x0026);
  // public static final CipherSuite TLS_KRB5_EXPORT_WITH_RC2_CBC_40_SHA = of("TLS_KRB5_EXPORT_WITH_RC2_CBC_40_SHA", 0x0027);
  public static final CipherSuite TLS_KRB5_EXPORT_WITH_RC4_40_SHA = of("TLS_KRB5_EXPORT_WITH_RC4_40_SHA", 0x0028);
  public static final CipherSuite TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5 = of("TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5", 0x0029);
  // public static final CipherSuite TLS_KRB5_EXPORT_WITH_RC2_CBC_40_MD5 = of("TLS_KRB5_EXPORT_WITH_RC2_CBC_40_MD5", 0x002a);
  public static final CipherSuite TLS_KRB5_EXPORT_WITH_RC4_40_MD5 = of("TLS_KRB5_EXPORT_WITH_RC4_40_MD5", 0x002b);
  // public static final CipherSuite TLS_PSK_WITH_NULL_SHA = of("TLS_PSK_WITH_NULL_SHA", 0x002c);
  // public static final CipherSuite TLS_DHE_PSK_WITH_NULL_SHA = of("TLS_DHE_PSK_WITH_NULL_SHA", 0x002d);
  // public static final CipherSuite TLS_RSA_PSK_WITH_NULL_SHA = of("TLS_RSA_PSK_WITH_NULL_SHA", 0x002e);
  public static final CipherSuite TLS_RSA_WITH_AES_128_CBC_SHA = of("TLS_RSA_WITH_AES_128_CBC_SHA", 0x002f);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_128_CBC_SHA = of("TLS_DH_DSS_WITH_AES_128_CBC_SHA", 0x0030);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_128_CBC_SHA = of("TLS_DH_RSA_WITH_AES_128_CBC_SHA", 0x0031);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_128_CBC_SHA = of("TLS_DHE_DSS_WITH_AES_128_CBC_SHA", 0x0032);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_CBC_SHA = of("TLS_DHE_RSA_WITH_AES_128_CBC_SHA", 0x0033);
  public static final CipherSuite TLS_DH_anon_WITH_AES_128_CBC_SHA = of("TLS_DH_anon_WITH_AES_128_CBC_SHA", 0x0034);
  public static final CipherSuite TLS_RSA_WITH_AES_256_CBC_SHA = of("TLS_RSA_WITH_AES_256_CBC_SHA", 0x0035);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_256_CBC_SHA = of("TLS_DH_DSS_WITH_AES_256_CBC_SHA", 0x0036);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_256_CBC_SHA = of("TLS_DH_RSA_WITH_AES_256_CBC_SHA", 0x0037);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_256_CBC_SHA = of("TLS_DHE_DSS_WITH_AES_256_CBC_SHA", 0x0038);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_CBC_SHA = of("TLS_DHE_RSA_WITH_AES_256_CBC_SHA", 0x0039);
  public static final CipherSuite TLS_DH_anon_WITH_AES_256_CBC_SHA = of("TLS_DH_anon_WITH_AES_256_CBC_SHA", 0x003a);
  public static final CipherSuite TLS_RSA_WITH_NULL_SHA256 = of("TLS_RSA_WITH_NULL_SHA256", 0x003b);
  public static final CipherSuite TLS_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_RSA_WITH_AES_128_CBC_SHA256", 0x003c);
  public static final CipherSuite TLS_RSA_WITH_AES_256_CBC_SHA256 = of("TLS_RSA_WITH_AES_256_CBC_SHA256", 0x003d);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_128_CBC_SHA256 = of("TLS_DH_DSS_WITH_AES_128_CBC_SHA256", 0x003e);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_DH_RSA_WITH_AES_128_CBC_SHA256", 0x003f);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_128_CBC_SHA256 = of("TLS_DHE_DSS_WITH_AES_128_CBC_SHA256", 0x0040);
  public static final CipherSuite TLS_RSA_WITH_CAMELLIA_128_CBC_SHA = of("TLS_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0041);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA", 0x0042);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0043);
  public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA", 0x0044);
  public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA", 0x0045);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA = of("TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA", 0x0046);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", 0x0067);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_256_CBC_SHA256 = of("TLS_DH_DSS_WITH_AES_256_CBC_SHA256", 0x0068);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_256_CBC_SHA256 = of("TLS_DH_RSA_WITH_AES_256_CBC_SHA256", 0x0069);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_256_CBC_SHA256 = of("TLS_DHE_DSS_WITH_AES_256_CBC_SHA256", 0x006a);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_CBC_SHA256 = of("TLS_DHE_RSA_WITH_AES_256_CBC_SHA256", 0x006b);
  public static final CipherSuite TLS_DH_anon_WITH_AES_128_CBC_SHA256 = of("TLS_DH_anon_WITH_AES_128_CBC_SHA256", 0x006c);
  public static final CipherSuite TLS_DH_anon_WITH_AES_256_CBC_SHA256 = of("TLS_DH_anon_WITH_AES_256_CBC_SHA256", 0x006d);
  public static final CipherSuite TLS_RSA_WITH_CAMELLIA_256_CBC_SHA = of("TLS_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0084);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA", 0x0085);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0086);
  public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA", 0x0087);
  public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA", 0x0088);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA = of("TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA", 0x0089);
  public static final CipherSuite TLS_PSK_WITH_RC4_128_SHA = of("TLS_PSK_WITH_RC4_128_SHA", 0x008a);
  public static final CipherSuite TLS_PSK_WITH_3DES_EDE_CBC_SHA = of("TLS_PSK_WITH_3DES_EDE_CBC_SHA", 0x008b);
  public static final CipherSuite TLS_PSK_WITH_AES_128_CBC_SHA = of("TLS_PSK_WITH_AES_128_CBC_SHA", 0x008c);
  public static final CipherSuite TLS_PSK_WITH_AES_256_CBC_SHA = of("TLS_PSK_WITH_AES_256_CBC_SHA", 0x008d);
  // public static final CipherSuite TLS_DHE_PSK_WITH_RC4_128_SHA = of("TLS_DHE_PSK_WITH_RC4_128_SHA", 0x008e);
  // public static final CipherSuite TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA = of("TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA", 0x008f);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_128_CBC_SHA = of("TLS_DHE_PSK_WITH_AES_128_CBC_SHA", 0x0090);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_256_CBC_SHA = of("TLS_DHE_PSK_WITH_AES_256_CBC_SHA", 0x0091);
  // public static final CipherSuite TLS_RSA_PSK_WITH_RC4_128_SHA = of("TLS_RSA_PSK_WITH_RC4_128_SHA", 0x0092);
  // public static final CipherSuite TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA = of("TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA", 0x0093);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_128_CBC_SHA = of("TLS_RSA_PSK_WITH_AES_128_CBC_SHA", 0x0094);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_256_CBC_SHA = of("TLS_RSA_PSK_WITH_AES_256_CBC_SHA", 0x0095);
  public static final CipherSuite TLS_RSA_WITH_SEED_CBC_SHA = of("TLS_RSA_WITH_SEED_CBC_SHA", 0x0096);
  // public static final CipherSuite TLS_DH_DSS_WITH_SEED_CBC_SHA = of("TLS_DH_DSS_WITH_SEED_CBC_SHA", 0x0097);
  // public static final CipherSuite TLS_DH_RSA_WITH_SEED_CBC_SHA = of("TLS_DH_RSA_WITH_SEED_CBC_SHA", 0x0098);
  // public static final CipherSuite TLS_DHE_DSS_WITH_SEED_CBC_SHA = of("TLS_DHE_DSS_WITH_SEED_CBC_SHA", 0x0099);
  // public static final CipherSuite TLS_DHE_RSA_WITH_SEED_CBC_SHA = of("TLS_DHE_RSA_WITH_SEED_CBC_SHA", 0x009a);
  // public static final CipherSuite TLS_DH_anon_WITH_SEED_CBC_SHA = of("TLS_DH_anon_WITH_SEED_CBC_SHA", 0x009b);
  public static final CipherSuite TLS_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_RSA_WITH_AES_128_GCM_SHA256", 0x009c);
  public static final CipherSuite TLS_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_RSA_WITH_AES_256_GCM_SHA384", 0x009d);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_DHE_RSA_WITH_AES_128_GCM_SHA256", 0x009e);
  public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", 0x009f);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_DH_RSA_WITH_AES_128_GCM_SHA256", 0x00a0);
  // public static final CipherSuite TLS_DH_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_DH_RSA_WITH_AES_256_GCM_SHA384", 0x00a1);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_128_GCM_SHA256 = of("TLS_DHE_DSS_WITH_AES_128_GCM_SHA256", 0x00a2);
  public static final CipherSuite TLS_DHE_DSS_WITH_AES_256_GCM_SHA384 = of("TLS_DHE_DSS_WITH_AES_256_GCM_SHA384", 0x00a3);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_128_GCM_SHA256 = of("TLS_DH_DSS_WITH_AES_128_GCM_SHA256", 0x00a4);
  // public static final CipherSuite TLS_DH_DSS_WITH_AES_256_GCM_SHA384 = of("TLS_DH_DSS_WITH_AES_256_GCM_SHA384", 0x00a5);
  public static final CipherSuite TLS_DH_anon_WITH_AES_128_GCM_SHA256 = of("TLS_DH_anon_WITH_AES_128_GCM_SHA256", 0x00a6);
  public static final CipherSuite TLS_DH_anon_WITH_AES_256_GCM_SHA384 = of("TLS_DH_anon_WITH_AES_256_GCM_SHA384", 0x00a7);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_GCM_SHA256 = of("TLS_PSK_WITH_AES_128_GCM_SHA256", 0x00a8);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_GCM_SHA384 = of("TLS_PSK_WITH_AES_256_GCM_SHA384", 0x00a9);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_128_GCM_SHA256 = of("TLS_DHE_PSK_WITH_AES_128_GCM_SHA256", 0x00aa);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_256_GCM_SHA384 = of("TLS_DHE_PSK_WITH_AES_256_GCM_SHA384", 0x00ab);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_128_GCM_SHA256 = of("TLS_RSA_PSK_WITH_AES_128_GCM_SHA256", 0x00ac);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_256_GCM_SHA384 = of("TLS_RSA_PSK_WITH_AES_256_GCM_SHA384", 0x00ad);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_CBC_SHA256 = of("TLS_PSK_WITH_AES_128_CBC_SHA256", 0x00ae);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_CBC_SHA384 = of("TLS_PSK_WITH_AES_256_CBC_SHA384", 0x00af);
  // public static final CipherSuite TLS_PSK_WITH_NULL_SHA256 = of("TLS_PSK_WITH_NULL_SHA256", 0x00b0);
  // public static final CipherSuite TLS_PSK_WITH_NULL_SHA384 = of("TLS_PSK_WITH_NULL_SHA384", 0x00b1);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_128_CBC_SHA256 = of("TLS_DHE_PSK_WITH_AES_128_CBC_SHA256", 0x00b2);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_256_CBC_SHA384 = of("TLS_DHE_PSK_WITH_AES_256_CBC_SHA384", 0x00b3);
  // public static final CipherSuite TLS_DHE_PSK_WITH_NULL_SHA256 = of("TLS_DHE_PSK_WITH_NULL_SHA256", 0x00b4);
  // public static final CipherSuite TLS_DHE_PSK_WITH_NULL_SHA384 = of("TLS_DHE_PSK_WITH_NULL_SHA384", 0x00b5);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_128_CBC_SHA256 = of("TLS_RSA_PSK_WITH_AES_128_CBC_SHA256", 0x00b6);
  // public static final CipherSuite TLS_RSA_PSK_WITH_AES_256_CBC_SHA384 = of("TLS_RSA_PSK_WITH_AES_256_CBC_SHA384", 0x00b7);
  // public static final CipherSuite TLS_RSA_PSK_WITH_NULL_SHA256 = of("TLS_RSA_PSK_WITH_NULL_SHA256", 0x00b8);
  // public static final CipherSuite TLS_RSA_PSK_WITH_NULL_SHA384 = of("TLS_RSA_PSK_WITH_NULL_SHA384", 0x00b9);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00ba);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256", 0x00bb);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00bc);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256", 0x00bd);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0x00be);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256", 0x00bf);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c0);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256", 0x00c1);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c2);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256", 0x00c3);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256", 0x00c4);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256 = of("TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256", 0x00c5);
  public static final CipherSuite TLS_EMPTY_RENEGOTIATION_INFO_SCSV = of("TLS_EMPTY_RENEGOTIATION_INFO_SCSV", 0x00ff);
  public static final CipherSuite TLS_FALLBACK_SCSV = of("TLS_FALLBACK_SCSV", 0x5600);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_NULL_SHA = of("TLS_ECDH_ECDSA_WITH_NULL_SHA", 0xc001);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_RC4_128_SHA = of("TLS_ECDH_ECDSA_WITH_RC4_128_SHA", 0xc002);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA", 0xc003);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA = of("TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA", 0xc004);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA = of("TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA", 0xc005);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_NULL_SHA = of("TLS_ECDHE_ECDSA_WITH_NULL_SHA", 0xc006);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_RC4_128_SHA = of("TLS_ECDHE_ECDSA_WITH_RC4_128_SHA", 0xc007);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA", 0xc008);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA = of("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", 0xc009);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA = of("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", 0xc00a);
  public static final CipherSuite TLS_ECDH_RSA_WITH_NULL_SHA = of("TLS_ECDH_RSA_WITH_NULL_SHA", 0xc00b);
  public static final CipherSuite TLS_ECDH_RSA_WITH_RC4_128_SHA = of("TLS_ECDH_RSA_WITH_RC4_128_SHA", 0xc00c);
  public static final CipherSuite TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA", 0xc00d);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_128_CBC_SHA = of("TLS_ECDH_RSA_WITH_AES_128_CBC_SHA", 0xc00e);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_256_CBC_SHA = of("TLS_ECDH_RSA_WITH_AES_256_CBC_SHA", 0xc00f);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_NULL_SHA = of("TLS_ECDHE_RSA_WITH_NULL_SHA", 0xc010);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_RC4_128_SHA = of("TLS_ECDHE_RSA_WITH_RC4_128_SHA", 0xc011);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA", 0xc012);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA = of("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", 0xc013);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA = of("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", 0xc014);
  public static final CipherSuite TLS_ECDH_anon_WITH_NULL_SHA = of("TLS_ECDH_anon_WITH_NULL_SHA", 0xc015);
  public static final CipherSuite TLS_ECDH_anon_WITH_RC4_128_SHA = of("TLS_ECDH_anon_WITH_RC4_128_SHA", 0xc016);
  public static final CipherSuite TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA", 0xc017);
  public static final CipherSuite TLS_ECDH_anon_WITH_AES_128_CBC_SHA = of("TLS_ECDH_anon_WITH_AES_128_CBC_SHA", 0xc018);
  public static final CipherSuite TLS_ECDH_anon_WITH_AES_256_CBC_SHA = of("TLS_ECDH_anon_WITH_AES_256_CBC_SHA", 0xc019);
  // public static final CipherSuite TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA = of("TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA", 0xc01a);
  // public static final CipherSuite TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA = of("TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA", 0xc01b);
  // public static final CipherSuite TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA = of("TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA", 0xc01c);
  // public static final CipherSuite TLS_SRP_SHA_WITH_AES_128_CBC_SHA = of("TLS_SRP_SHA_WITH_AES_128_CBC_SHA", 0xc01d);
  // public static final CipherSuite TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA = of("TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA", 0xc01e);
  // public static final CipherSuite TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA = of("TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA", 0xc01f);
  // public static final CipherSuite TLS_SRP_SHA_WITH_AES_256_CBC_SHA = of("TLS_SRP_SHA_WITH_AES_256_CBC_SHA", 0xc020);
  // public static final CipherSuite TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA = of("TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA", 0xc021);
  // public static final CipherSuite TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA = of("TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA", 0xc022);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256 = of("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", 0xc023);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384 = of("TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384", 0xc024);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256 = of("TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256", 0xc025);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384 = of("TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384", 0xc026);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", 0xc027);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384 = of("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", 0xc028);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256 = of("TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256", 0xc029);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384 = of("TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384", 0xc02a);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 = of("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", 0xc02b);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384 = of("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", 0xc02c);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256 = of("TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256", 0xc02d);
  public static final CipherSuite TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384 = of("TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384", 0xc02e);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", 0xc02f);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", 0xc030);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256 = of("TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256", 0xc031);
  public static final CipherSuite TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384 = of("TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384", 0xc032);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_RC4_128_SHA = of("TLS_ECDHE_PSK_WITH_RC4_128_SHA", 0xc033);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA = of("TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA", 0xc034);
  public static final CipherSuite TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA = of("TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA", 0xc035);
  public static final CipherSuite TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA = of("TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA", 0xc036);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256 = of("TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256", 0xc037);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384 = of("TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384", 0xc038);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_NULL_SHA = of("TLS_ECDHE_PSK_WITH_NULL_SHA", 0xc039);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_NULL_SHA256 = of("TLS_ECDHE_PSK_WITH_NULL_SHA256", 0xc03a);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_NULL_SHA384 = of("TLS_ECDHE_PSK_WITH_NULL_SHA384", 0xc03b);
  // public static final CipherSuite TLS_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_RSA_WITH_ARIA_128_CBC_SHA256", 0xc03c);
  // public static final CipherSuite TLS_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_RSA_WITH_ARIA_256_CBC_SHA384", 0xc03d);
  // public static final CipherSuite TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256 = of("TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256", 0xc03e);
  // public static final CipherSuite TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384 = of("TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384", 0xc03f);
  // public static final CipherSuite TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256", 0xc040);
  // public static final CipherSuite TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384", 0xc041);
  // public static final CipherSuite TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256 = of("TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256", 0xc042);
  // public static final CipherSuite TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384 = of("TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384", 0xc043);
  // public static final CipherSuite TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256", 0xc044);
  // public static final CipherSuite TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384", 0xc045);
  // public static final CipherSuite TLS_DH_anon_WITH_ARIA_128_CBC_SHA256 = of("TLS_DH_anon_WITH_ARIA_128_CBC_SHA256", 0xc046);
  // public static final CipherSuite TLS_DH_anon_WITH_ARIA_256_CBC_SHA384 = of("TLS_DH_anon_WITH_ARIA_256_CBC_SHA384", 0xc047);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256", 0xc048);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384", 0xc049);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256", 0xc04a);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384", 0xc04b);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256", 0xc04c);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384", 0xc04d);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256", 0xc04e);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384", 0xc04f);
  // public static final CipherSuite TLS_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_RSA_WITH_ARIA_128_GCM_SHA256", 0xc050);
  // public static final CipherSuite TLS_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_RSA_WITH_ARIA_256_GCM_SHA384", 0xc051);
  // public static final CipherSuite TLS_DHE_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_DHE_RSA_WITH_ARIA_128_GCM_SHA256", 0xc052);
  // public static final CipherSuite TLS_DHE_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_DHE_RSA_WITH_ARIA_256_GCM_SHA384", 0xc053);
  // public static final CipherSuite TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256", 0xc054);
  // public static final CipherSuite TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384", 0xc055);
  // public static final CipherSuite TLS_DHE_DSS_WITH_ARIA_128_GCM_SHA256 = of("TLS_DHE_DSS_WITH_ARIA_128_GCM_SHA256", 0xc056);
  // public static final CipherSuite TLS_DHE_DSS_WITH_ARIA_256_GCM_SHA384 = of("TLS_DHE_DSS_WITH_ARIA_256_GCM_SHA384", 0xc057);
  // public static final CipherSuite TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256 = of("TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256", 0xc058);
  // public static final CipherSuite TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384 = of("TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384", 0xc059);
  // public static final CipherSuite TLS_DH_anon_WITH_ARIA_128_GCM_SHA256 = of("TLS_DH_anon_WITH_ARIA_128_GCM_SHA256", 0xc05a);
  // public static final CipherSuite TLS_DH_anon_WITH_ARIA_256_GCM_SHA384 = of("TLS_DH_anon_WITH_ARIA_256_GCM_SHA384", 0xc05b);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_ECDHE_ECDSA_WITH_ARIA_128_GCM_SHA256", 0xc05c);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_ECDHE_ECDSA_WITH_ARIA_256_GCM_SHA384", 0xc05d);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256", 0xc05e);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384", 0xc05f);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_ECDHE_RSA_WITH_ARIA_128_GCM_SHA256", 0xc060);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_ECDHE_RSA_WITH_ARIA_256_GCM_SHA384", 0xc061);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256 = of("TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256", 0xc062);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384 = of("TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384", 0xc063);
  // public static final CipherSuite TLS_PSK_WITH_ARIA_128_CBC_SHA256 = of("TLS_PSK_WITH_ARIA_128_CBC_SHA256", 0xc064);
  // public static final CipherSuite TLS_PSK_WITH_ARIA_256_CBC_SHA384 = of("TLS_PSK_WITH_ARIA_256_CBC_SHA384", 0xc065);
  // public static final CipherSuite TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256 = of("TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256", 0xc066);
  // public static final CipherSuite TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384 = of("TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384", 0xc067);
  // public static final CipherSuite TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256 = of("TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256", 0xc068);
  // public static final CipherSuite TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384 = of("TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384", 0xc069);
  // public static final CipherSuite TLS_PSK_WITH_ARIA_128_GCM_SHA256 = of("TLS_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06a);
  // public static final CipherSuite TLS_PSK_WITH_ARIA_256_GCM_SHA384 = of("TLS_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06b);
  // public static final CipherSuite TLS_DHE_PSK_WITH_ARIA_128_GCM_SHA256 = of("TLS_DHE_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06c);
  // public static final CipherSuite TLS_DHE_PSK_WITH_ARIA_256_GCM_SHA384 = of("TLS_DHE_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06d);
  // public static final CipherSuite TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256 = of("TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256", 0xc06e);
  // public static final CipherSuite TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384 = of("TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384", 0xc06f);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256 = of("TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256", 0xc070);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384 = of("TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384", 0xc071);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc072);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc073);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc074);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc075);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc076);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc077);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256", 0xc078);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384", 0xc079);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07a);
  // public static final CipherSuite TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07b);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DHE_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07c);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DHE_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07d);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc07e);
  // public static final CipherSuite TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc07f);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DHE_DSS_WITH_CAMELLIA_128_GCM_SHA256", 0xc080);
  // public static final CipherSuite TLS_DHE_DSS_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DHE_DSS_WITH_CAMELLIA_256_GCM_SHA384", 0xc081);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256", 0xc082);
  // public static final CipherSuite TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384", 0xc083);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256", 0xc084);
  // public static final CipherSuite TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384", 0xc085);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc086);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc087);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc088);
  // public static final CipherSuite TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc089);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_ECDHE_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc08a);
  // public static final CipherSuite TLS_ECDHE_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_ECDHE_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc08b);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256", 0xc08c);
  // public static final CipherSuite TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384", 0xc08d);
  // public static final CipherSuite TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc08e);
  // public static final CipherSuite TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc08f);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_DHE_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc090);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_DHE_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc091);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256 = of("TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256", 0xc092);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384 = of("TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384", 0xc093);
  // public static final CipherSuite TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc094);
  // public static final CipherSuite TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc095);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc096);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc097);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc098);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc099);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256 = of("TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256", 0xc09a);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384 = of("TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384", 0xc09b);
  // public static final CipherSuite TLS_RSA_WITH_AES_128_CCM = of("TLS_RSA_WITH_AES_128_CCM", 0xc09c);
  // public static final CipherSuite TLS_RSA_WITH_AES_256_CCM = of("TLS_RSA_WITH_AES_256_CCM", 0xc09d);
  // public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_CCM = of("TLS_DHE_RSA_WITH_AES_128_CCM", 0xc09e);
  // public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_CCM = of("TLS_DHE_RSA_WITH_AES_256_CCM", 0xc09f);
  // public static final CipherSuite TLS_RSA_WITH_AES_128_CCM_8 = of("TLS_RSA_WITH_AES_128_CCM_8", 0xc0a0);
  // public static final CipherSuite TLS_RSA_WITH_AES_256_CCM_8 = of("TLS_RSA_WITH_AES_256_CCM_8", 0xc0a1);
  // public static final CipherSuite TLS_DHE_RSA_WITH_AES_128_CCM_8 = of("TLS_DHE_RSA_WITH_AES_128_CCM_8", 0xc0a2);
  // public static final CipherSuite TLS_DHE_RSA_WITH_AES_256_CCM_8 = of("TLS_DHE_RSA_WITH_AES_256_CCM_8", 0xc0a3);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_CCM = of("TLS_PSK_WITH_AES_128_CCM", 0xc0a4);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_CCM = of("TLS_PSK_WITH_AES_256_CCM", 0xc0a5);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_128_CCM = of("TLS_DHE_PSK_WITH_AES_128_CCM", 0xc0a6);
  // public static final CipherSuite TLS_DHE_PSK_WITH_AES_256_CCM = of("TLS_DHE_PSK_WITH_AES_256_CCM", 0xc0a7);
  // public static final CipherSuite TLS_PSK_WITH_AES_128_CCM_8 = of("TLS_PSK_WITH_AES_128_CCM_8", 0xc0a8);
  // public static final CipherSuite TLS_PSK_WITH_AES_256_CCM_8 = of("TLS_PSK_WITH_AES_256_CCM_8", 0xc0a9);
  // public static final CipherSuite TLS_PSK_DHE_WITH_AES_128_CCM_8 = of("TLS_PSK_DHE_WITH_AES_128_CCM_8", 0xc0aa);
  // public static final CipherSuite TLS_PSK_DHE_WITH_AES_256_CCM_8 = of("TLS_PSK_DHE_WITH_AES_256_CCM_8", 0xc0ab);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_CCM = of("TLS_ECDHE_ECDSA_WITH_AES_128_CCM", 0xc0ac);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_CCM = of("TLS_ECDHE_ECDSA_WITH_AES_256_CCM", 0xc0ad);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8 = of("TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8", 0xc0ae);
  // public static final CipherSuite TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8 = of("TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8", 0xc0af);
  public static final CipherSuite TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256 = of("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", 0xcca8);
  public static final CipherSuite TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 = of("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", 0xcca9);
  // public static final CipherSuite TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256 = of("TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256", 0xccaa);
  // public static final CipherSuite TLS_PSK_WITH_CHACHA20_POLY1305_SHA256 = of("TLS_PSK_WITH_CHACHA20_POLY1305_SHA256", 0xccab);
  // public static final CipherSuite TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256 = of("TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256", 0xccac);
  // public static final CipherSuite TLS_DHE_PSK_WITH_CHACHA20_POLY1305_SHA256 = of("TLS_DHE_PSK_WITH_CHACHA20_POLY1305_SHA256", 0xccad);
  // public static final CipherSuite TLS_RSA_PSK_WITH_CHACHA20_POLY1305_SHA256 = of("TLS_RSA_PSK_WITH_CHACHA20_POLY1305_SHA256", 0xccae);

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
   */
  private static CipherSuite of(String javaName, int value) {
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

  @Override public String toString() {
    return javaName;
  }
}
