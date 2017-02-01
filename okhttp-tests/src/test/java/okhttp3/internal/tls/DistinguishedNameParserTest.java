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
import static org.junit.Assert.fail;

public final class DistinguishedNameParserTest {
  @Test public void regularCases() {
    assertCn("xxx", "ou=xxx,cn=xxx");
    assertCn("yyy", "ou=xxx+cn=yyy,cn=zzz+cn=abc");
    assertCn("a", "cn=a,cn=b");
    assertCn("a   c", "cn=a   c,cn=b");
    assertCn("Cc", "cn=Cc,cn=Bb,cn=Aa");
    assertCn("imap.gmail.com", "cn=imap.gmail.com");
    assertCn("c", "l=\"abcn=a,b\", cn=c");
    assertCn("c", "l=\"abcn=a,b\", cn=c");
    assertCn("c", "l=\"abcn=a,b\", cn= c");
    assertCn("a b", "cn=a b");
    assertCn("a b", "cn   =a b");
    assertCn("a b", "Cn=a b");
    assertCn("a b", "cN=a b");
    assertCn("a b", "CN=a b");
    assertCn("a#b", "cn=a#b");
    assertCn("a", "cn=#130161");
    assertCn("p", "l=q\t+cn=p");
    assertCn("p", "l=q\n+cn=p");
    assertCn("p", "l=q\n,cn=p");
    assertCn("p", "l=,cn=p");
    assertCn("\tp", "l=\tq\n,cn=\tp");
  }

  @Test public void emptyValues() {
    assertCn(null, "");
    assertCn(null, "ou=xxx");
    assertCn("", "l=,cn=+cn=q");
    assertCn("", "l=,cn=,cn=q");
    assertCn(null, "l=,cn=");
    assertCn("q", "l=,cn=q,cn=   ");
    assertCn("q", "l=,cn=q  ,cn=   ");
    assertCn(null, "l=,cn=\"\"");
    assertCn("", "l=,cn=  ,cn=  ");
    assertCn("", "l=,cn=,cn=  ,cn=  ");
  }

  @Test public void escapedChars() {
    assertCn(",", "cn=\\,");
    assertCn("#", "cn=\\#");
    assertCn("+", "cn=\\+");
    assertCn("\"", "cn=\\\"");
    assertCn("\\", "cn=\\\\");
    assertCn("<", "cn=\\<");
    assertCn(">", "cn=\\>");
    assertCn(";", "cn=\\;");
    assertCn("+", "cn=\\+");
    assertCn("+", "cn=\"\\+\"");
    assertCn(",", "cn=\"\\,\"");
    assertCn("a =", "cn= a =");
    assertCn("=", "cn==");
  }

  @Test public void whitespace() {
    assertCn("p", "cn= p");
    assertCn("p", "cn=\np");
    assertCn("\tp", "cn=\tp");
  }

  @Test public void withOid() {
    assertCn("a", "2.5.4.3=a,ou=xxx");
    assertCn("a", "2.5.4.3=\" a \",ou=xxx");
    assertCn("b", "2.5.5.3=a,ou=xxx,cn=b");
  }

  @Test public void quotedStrings() {
    assertCn("\" a ,=<>#;", "cn=\"\\\" a ,=<>#;\"");
    assertCn("abc,def", "cn=abc\\,def");
    assertCn("\" a ,=<>#;", "cn=\"\\\" a ,\\=<>\\#;\"");
  }

  @Test public void utf8() {
    assertCn("\u004c\u0075\u010d\u0069\u0107", "cn=\"Lu\\C4\\8Di\\C4\\87\"");
    assertCn("\u004c\u0075\u010d\u0069\u0107", "cn=Lu\\C4\\8Di\\C4\\87");
    assertCn("\u004c\u0075\u010d\u0069\u0107", "cn=Lu\\C4\\8di\\c4\\87");
    assertCn("\u004c\u0075\u010d\u0069\u0107", "cn=\"Lu\\C4\\8di\\c4\\87\"");
    assertCn("\u004c\u0075\u010d\u0069\u0107", "cn=\u004c\u0075\u010d\u0069\u0107");
    // \63=c
    expectExceptionInPrincipal("\\63n=ab");
    expectExceptionInPrincipal("cn=\\a");
  }

  @Test public void trailingWhitespace() {
    assertCn("a  b", "ou=a, cn=  a  b  ,o=x");
    assertCn("a  b", "cn=\"  a  b  \" ,o=x");
    assertCn("a", "cn=a   ,cn=b");
    assertCn("", "l=,cn=\"  \",cn=\"  \"");
  }

  /**
   * @param expected the value of the first "cn=" argument in {@code dn},
   *                 or null if none is expected
   */
  private void assertCn(String expected, String dn) {
    X500Principal principal = new X500Principal(dn);
    DistinguishedNameParser parser = new DistinguishedNameParser(principal);
    assertEquals(dn, expected, parser.findMostSpecific("cn"));
  }

  private void expectExceptionInPrincipal(String dn) {
    try {
      new X500Principal(dn);
      fail("Expected " + IllegalArgumentException.class.getName()
          + " because of incorrect input name");
    } catch (IllegalArgumentException expected) {
    }
  }
}
