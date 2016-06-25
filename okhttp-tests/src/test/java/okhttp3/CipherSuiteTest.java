/*
 * Copyright (C) 2016 Google Inc.
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

import org.junit.Test;

import static okhttp3.CipherSuite.TLS_KRB5_WITH_DES_CBC_MD5;
import static okhttp3.CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5;
import static okhttp3.CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256;
import static okhttp3.CipherSuite.forJavaName;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class CipherSuiteTest {
  @Test public void nullCipherName() {
    try {
      forJavaName(null);
      fail("Should have thrown");
    } catch (NullPointerException expected) {
    }
  }

  @Test public void hashCode_usesIdentityHashCode_legacyCase() {
    CipherSuite cs = TLS_RSA_EXPORT_WITH_RC4_40_MD5; // This one's javaName starts with "SSL_".
    assertEquals(cs.toString(), System.identityHashCode(cs), cs.hashCode());
  }

  @Test public void hashCode_usesIdentityHashCode_regularCase() {
    CipherSuite cs = TLS_RSA_WITH_AES_128_CBC_SHA256; // This one's javaName matches the identifier.
    assertEquals(cs.toString(), System.identityHashCode(cs), cs.hashCode());
  }

  @Test public void instancesAreInterned() {
    assertSame(forJavaName("TestCipherSuite"), forJavaName("TestCipherSuite"));
    assertSame(CipherSuite.TLS_KRB5_WITH_DES_CBC_MD5,
        forJavaName(TLS_KRB5_WITH_DES_CBC_MD5.javaName()));
  }

  /**
   * Tests that interned CipherSuite instances remain the case across garbage collections, even if
   * the String used to construct them is no longer strongly referenced outside of the CipherSuite.
   */
  @SuppressWarnings("RedundantStringConstructorCall")
  @Test public void instancesAreInterned_survivesGarbageCollection() {
    // We're not holding onto a reference to this String instance outside of the CipherSuite...
    CipherSuite cs = forJavaName(new String("FakeCipherSuite_instancesAreInterned"));
    System.gc(); // Unless cs references the String instance, it may now be garbage collected.
    assertSame(cs, forJavaName(new String(cs.javaName())));
  }

  @Test public void equals() {
    assertEquals(forJavaName("cipher"), forJavaName("cipher"));
    assertNotEquals(forJavaName("cipherA"), forJavaName("cipherB"));
    assertEquals(forJavaName("SSL_RSA_EXPORT_WITH_RC4_40_MD5"), TLS_RSA_EXPORT_WITH_RC4_40_MD5);
    assertNotEquals(TLS_RSA_EXPORT_WITH_RC4_40_MD5, TLS_RSA_WITH_AES_128_CBC_SHA256);
  }

  @Test public void forJavaName_acceptsArbitraryStrings() {
    // Shouldn't throw.
    forJavaName("example CipherSuite name that is not in the whitelist");
  }

  @Test public void javaName_examples() {
    assertEquals("SSL_RSA_EXPORT_WITH_RC4_40_MD5", TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName());
    assertEquals("TLS_RSA_WITH_AES_128_CBC_SHA256", TLS_RSA_WITH_AES_128_CBC_SHA256.javaName());
    assertEquals("TestCipherSuite", forJavaName("TestCipherSuite").javaName());
  }

  @Test public void javaName_equalsToString() {
    assertEquals(TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName,
        TLS_RSA_EXPORT_WITH_RC4_40_MD5.toString());
    assertEquals(TLS_RSA_WITH_AES_128_CBC_SHA256.javaName,
        TLS_RSA_WITH_AES_128_CBC_SHA256.toString());
  }

  /**
   * Legacy ciphers (whose javaName starts with "SSL_") are now considered different from the
   * corresponding "TLS_" ciphers. In OkHttp 3.3.1, only 19 of those would have been valid; those 19
   * would have been considered equal to the corresponding "TLS_" ciphers.
   */
  @Test public void forJavaName_fromLegacyEnumName() {
    // These would have been considered equal in OkHttp 3.3.1, but now aren't.
    assertNotEquals(
        forJavaName("TLS_RSA_EXPORT_WITH_RC4_40_MD5"),
        forJavaName("SSL_RSA_EXPORT_WITH_RC4_40_MD5"));

    // The SSL_ one of these would have been invalid in OkHttp 3.3.1; it now is valid and not equal.
    assertNotEquals(
        forJavaName("TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA"),
        forJavaName("SSL_DH_RSA_EXPORT_WITH_DES40_CBC_SHA"));

    // These would have not been valid in OkHttp 3.3.1, and now aren't equal.
    assertNotEquals(
        forJavaName("TLS_FAKE_NEW_CIPHER"),
        forJavaName("SSL_FAKE_NEW_CIPHER"));
  }
}
