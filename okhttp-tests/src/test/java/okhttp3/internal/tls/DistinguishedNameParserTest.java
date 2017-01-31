/*
 * Copyright (C) 2010 The Android Open Source Project
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
package okhttp3.internal.tls;


import javax.security.auth.x500.X500Principal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public final class DistinguishedNameParserTest {
  @Test public void testGetCns() {
    assertCns("");
    assertCns("ou=xxx");
    assertCns("ou=xxx,cn=xxx", "xxx");
    assertCns("ou=xxx+cn=yyy,cn=zzz+cn=abc", "yyy", "zzz", "abc");
    assertCns("cn=a,cn=b", "a", "b");
    assertCns("cn=a   c,cn=b", "a   c", "b");
    assertCns("cn=a   ,cn=b", "a", "b");
    assertCns("cn=Cc,cn=Bb,cn=Aa", "Cc", "Bb", "Aa");
    assertCns("cn=imap.gmail.com", "imap.gmail.com");
    assertCns("l=\"abcn=a,b\", cn=c", "c");
    assertCns("l=\"abcn=a,b\", cn=c", "c");
    assertCns("l=\"abcn=a,b\", cn= c", "c");
    assertCns("cn=<", "<");
    assertCns("cn=>", ">");
    assertCns("cn= >", ">");
    assertCns("cn=a b", "a b");
    assertCns("cn   =a b", "a b");
    assertCns("Cn=a b", "a b");
    assertCns("cN=a b", "a b");
    assertCns("CN=a b", "a b");
    assertCns("cn=a#b", "a#b");
    assertCns("cn=#130161", "a");
    assertCns("l=q\t+cn=p", "p");
    assertCns("l=q\n+cn=p", "p");
    assertCns("l=q\n,cn=p", "p");
    assertCns("l=,cn=p", "p");
    assertCns("l=\tq\n,cn=\tp", "\tp");
  }

  /** A cn=, generates an empty value, unless it's at the very end. */
  @Test public void emptyValues() {
    assertCns("l=,cn=+cn=q", "", "q");
    assertCns("l=,cn=,cn=q", "", "q");
    assertCns("l=,cn=");
    assertCns("l=,cn=q,cn=   ", "q");
    assertCns("l=,cn=q  ,cn=   ", "q");
    assertCns("l=,cn=\"\"");
    assertCns("l=,cn=\"  \",cn=\"  \"","  ");
    assertCns("l=,cn=  ,cn=  ","");
    assertCns("l=,cn=,cn=  ,cn=  ", "", "");
  }


  @Test public void testGetCns_escapedChars() {
    assertCns("cn=\\,", ",");
    assertCns("cn=\\#", "#");
    assertCns("cn=\\+", "+");
    assertCns("cn=\\\"", "\"");
    assertCns("cn=\\\\", "\\");
    assertCns("cn=\\<", "<");
    assertCns("cn=\\>", ">");
    assertCns("cn=\\;", ";");
    assertCns("cn=\\+", "+");
    assertCns("cn=\"\\+\"", "+");
    assertCns("cn=\"\\,\"", ",");
    assertCns("cn= a =", "a =");
    assertCns("cn==", "=");
  }

  @Test public void testGetCns_whitespace() {
    assertCns("cn= p", "p");
    assertCns("cn=\np", "\np");
    assertCns("cn=\tp", "\tp");
  }

  @Test public void testGetCnsWithOid() {
    assertCns("2.5.4.3=a,ou=xxx", "a");
    assertCns("2.5.4.3=\" a \",ou=xxx", " a ");
    assertCns("2.5.5.3=a,ou=xxx,cn=b", "b");
  }

  @Test public void testGetCnsWithQuotedStrings() {
    assertCns("cn=\"\\\" a ,=<>#;\"", "\" a ,=<>#;");
    assertCns("cn=abc\\,def", "abc,def");
    assertCns("cn=\"\\\" a ,\\=<>\\#;\"", "\" a ,=<>#;");
  }

  @Test public void testGetCnsWithUtf8() {
    assertCns("cn=\"Lu\\C4\\8Di\\C4\\87\"", "\u004c\u0075\u010d\u0069\u0107");
    assertCns("cn=Lu\\C4\\8Di\\C4\\87", "\u004c\u0075\u010d\u0069\u0107");
    assertCns("cn=Lu\\C4\\8di\\c4\\87", "\u004c\u0075\u010d\u0069\u0107");
    assertCns("cn=\"Lu\\C4\\8di\\c4\\87\"", "\u004c\u0075\u010d\u0069\u0107");
    assertCns("cn=\u004c\u0075\u010d\u0069\u0107", "\u004c\u0075\u010d\u0069\u0107");
    // \63=c
    expectExceptionInPrincipal("\\63n=ab");
    expectExceptionInPrincipal("cn=\\a");
  }

  @Test public void testGetCnsWithWhitespace() {

    assertCns("ou=a, cn=  a  b  ,o=x", "a  b");
    assertCns("cn=\"  a  b  \" ,o=x", "  a  b  ");
  }

  private void assertCns(String dn, String... expected) {
    X500Principal principal = new X500Principal(dn);
    DistinguishedNameParser parser = new DistinguishedNameParser(principal);
    // Test getAllMostSpecificFirst
//        assertEquals(dn, Arrays.asList(expected), parser.getAllMostSpecificFirst("cn"));

    // Test findMostSpecific
    if (expected.length > 0) {
      assertEquals(dn, expected[0], parser.findMostSpecific("cn"));
    } else {
      assertNull(dn, parser.findMostSpecific("cn"));
    }
  }

  private void assertGetAttribute(String dn, String attribute, String... expected) {
    X500Principal principal = new X500Principal(dn);
    DistinguishedNameParser parser = new DistinguishedNameParser(principal);
    // Test getAllMostSpecificFirst
//        assertEquals(dn, Arrays.asList(expected), parser.getAllMostSpecificFirst(attribute));

    // Test findMostSpecific
    if (expected.length > 0) {
      assertEquals(dn, expected[0], parser.findMostSpecific(attribute));
    } else {
      assertNull(dn, parser.findMostSpecific(attribute));
    }
  }

  private void expectExceptionInPrincipal(String dn) {
    try {
      X500Principal principal = new X500Principal(dn);
      fail("Expected " + IllegalArgumentException.class.getName()
          + " because of incorrect input name");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }
}
