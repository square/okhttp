/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.tls;

import javax.security.auth.x500.X500Principal;

/**
 * A distinguished name (DN) parser. This parser only supports extracting a string value from a DN.
 * It doesn't support values in the hex-string style.
 */
final class DistinguishedNameParser {
  private final String dn;
  private final int length;
  private int pos;
  private int beg;
  private int end;

  /** Temporary variable to store positions of the currently parsed item. */
  private int cur;

  /** Distinguished name characters. */
  private char[] chars;

  public DistinguishedNameParser(X500Principal principal) {
    // RFC2253 is used to ensure we get attributes in the reverse
    // order of the underlying ASN.1 encoding, so that the most
    // significant values of repeated attributes occur first.
    this.dn = principal.getName(X500Principal.RFC2253);
    this.length = this.dn.length();
  }

  // gets next attribute type: (ALPHA 1*keychar) / oid
  private String nextAT() {
    // skip preceding space chars, they can present after
    // comma or semicolon (compatibility with RFC 1779)
    for (; pos < length && chars[pos] == ' '; pos++) {
    }
    if (pos == length) {
      return null; // reached the end of DN
    }

    // mark the beginning of attribute type
    beg = pos;

    // attribute type chars
    pos++;
    for (; pos < length && chars[pos] != '=' && chars[pos] != ' '; pos++) {
      // we don't follow exact BNF syntax here:
      // accept any char except space and '='
    }
    if (pos >= length) {
      throw new IllegalStateException("Unexpected end of DN: " + dn);
    }

    // mark the end of attribute type
    end = pos;

    // skip trailing space chars between attribute type and '='
    // (compatibility with RFC 1779)
    if (chars[pos] == ' ') {
      for (; pos < length && chars[pos] != '=' && chars[pos] == ' '; pos++) {
      }

      if (chars[pos] != '=' || pos == length) {
        throw new IllegalStateException("Unexpected end of DN: " + dn);
      }
    }

    pos++; //skip '=' char

    // skip space chars between '=' and attribute value
    // (compatibility with RFC 1779)
    for (; pos < length && chars[pos] == ' '; pos++) {
    }

    // in case of oid attribute type skip its prefix: "oid." or "OID."
    // (compatibility with RFC 1779)
    if ((end - beg > 4) && (chars[beg + 3] == '.')
        && (chars[beg] == 'O' || chars[beg] == 'o')
        && (chars[beg + 1] == 'I' || chars[beg + 1] == 'i')
        && (chars[beg + 2] == 'D' || chars[beg + 2] == 'd')) {
      beg += 4;
    }

    return new String(chars, beg, end - beg);
  }

  // gets quoted attribute value: QUOTATION *( quotechar / pair ) QUOTATION
  private String quotedAV() {
    pos++;
    beg = pos;
    end = beg;
    while (true) {

      if (pos == length) {
        throw new IllegalStateException("Unexpected end of DN: " + dn);
      }

      if (chars[pos] == '"') {
        // enclosing quotation was found
        pos++;
        break;
      } else if (chars[pos] == '\\') {
        chars[end] = getEscaped();
      } else {
        // shift char: required for string with escaped chars
        chars[end] = chars[pos];
      }
      pos++;
      end++;
    }

    // skip trailing space chars before comma or semicolon.
    // (compatibility with RFC 1779)
    for (; pos < length && chars[pos] == ' '; pos++) {
    }

    return new String(chars, beg, end - beg);
  }

  // gets hex string attribute value: "#" hexstring
  private String hexAV() {
    if (pos + 4 >= length) {
      // encoded byte array  must be not less then 4 c
      throw new IllegalStateException("Unexpected end of DN: " + dn);
    }

    beg = pos; // store '#' position
    pos++;
    while (true) {

      // check for end of attribute value
      // looks for space and component separators
      if (pos == length || chars[pos] == '+' || chars[pos] == ','
          || chars[pos] == ';') {
        end = pos;
        break;
      }

      if (chars[pos] == ' ') {
        end = pos;
        pos++;
        // skip trailing space chars before comma or semicolon.
        // (compatibility with RFC 1779)
        for (; pos < length && chars[pos] == ' '; pos++) {
        }
        break;
      } else if (chars[pos] >= 'A' && chars[pos] <= 'F') {
        chars[pos] += 32; //to low case
      }

      pos++;
    }

    // verify length of hex string
    // encoded byte array  must be not less then 4 and must be even number
    int hexLen = end - beg; // skip first '#' char
    if (hexLen < 5 || (hexLen & 1) == 0) {
      throw new IllegalStateException("Unexpected end of DN: " + dn);
    }

    // get byte encoding from string representation
    byte[] encoded = new byte[hexLen / 2];
    for (int i = 0, p = beg + 1; i < encoded.length; p += 2, i++) {
      encoded[i] = (byte) getByte(p);
    }

    return new String(chars, beg, hexLen);
  }

  // gets string attribute value: *( stringchar / pair )
  private String escapedAV() {
    beg = pos;
    end = pos;
    while (true) {
      if (pos >= length) {
        // the end of DN has been found
        return new String(chars, beg, end - beg);
      }

      switch (chars[pos]) {
        case '+':
        case ',':
        case ';':
          // separator char has been found
          return new String(chars, beg, end - beg);
        case '\\':
          // escaped char
          chars[end++] = getEscaped();
          pos++;
          break;
        case ' ':
          // need to figure out whether space defines
          // the end of attribute value or not
          cur = end;

          pos++;
          chars[end++] = ' ';

          for (; pos < length && chars[pos] == ' '; pos++) {
            chars[end++] = ' ';
          }
          if (pos == length || chars[pos] == ',' || chars[pos] == '+'
              || chars[pos] == ';') {
            // separator char or the end of DN has been found
            return new String(chars, beg, cur - beg);
          }
          break;
        default:
          chars[end++] = chars[pos];
          pos++;
      }
    }
  }

  // returns escaped char
  private char getEscaped() {
    pos++;
    if (pos == length) {
      throw new IllegalStateException("Unexpected end of DN: " + dn);
    }

    switch (chars[pos]) {
      case '"':
      case '\\':
      case ',':
      case '=':
      case '+':
      case '<':
      case '>':
      case '#':
      case ';':
      case ' ':
      case '*':
      case '%':
      case '_':
        //FIXME: escaping is allowed only for leading or trailing space char
        return chars[pos];
      default:
        // RFC doesn't explicitly say that escaped hex pair is
        // interpreted as UTF-8 char. It only contains an example of such DN.
        return getUTF8();
    }
  }

  // decodes UTF-8 char
  // see http://www.unicode.org for UTF-8 bit distribution table
  private char getUTF8() {
    int res = getByte(pos);
    pos++; //FIXME tmp

    if (res < 128) { // one byte: 0-7F
      return (char) res;
    } else if (res >= 192 && res <= 247) {

      int count;
      if (res <= 223) { // two bytes: C0-DF
        count = 1;
        res = res & 0x1F;
      } else if (res <= 239) { // three bytes: E0-EF
        count = 2;
        res = res & 0x0F;
      } else { // four bytes: F0-F7
        count = 3;
        res = res & 0x07;
      }

      int b;
      for (int i = 0; i < count; i++) {
        pos++;
        if (pos == length || chars[pos] != '\\') {
          return 0x3F; //FIXME failed to decode UTF-8 char - return '?'
        }
        pos++;

        b = getByte(pos);
        pos++; //FIXME tmp
        if ((b & 0xC0) != 0x80) {
          return 0x3F; //FIXME failed to decode UTF-8 char - return '?'
        }

        res = (res << 6) + (b & 0x3F);
      }
      return (char) res;
    } else {
      return 0x3F; //FIXME failed to decode UTF-8 char - return '?'
    }
  }

  // Returns byte representation of a char pair
  // The char pair is composed of DN char in
  // specified 'position' and the next char
  // According to BNF syntax:
  // hexchar    = DIGIT / "A" / "B" / "C" / "D" / "E" / "F"
  //                    / "a" / "b" / "c" / "d" / "e" / "f"
  private int getByte(int position) {
    if (position + 1 >= length) {
      throw new IllegalStateException("Malformed DN: " + dn);
    }

    int b1, b2;

    b1 = chars[position];
    if (b1 >= '0' && b1 <= '9') {
      b1 = b1 - '0';
    } else if (b1 >= 'a' && b1 <= 'f') {
      b1 = b1 - 87; // 87 = 'a' - 10
    } else if (b1 >= 'A' && b1 <= 'F') {
      b1 = b1 - 55; // 55 = 'A' - 10
    } else {
      throw new IllegalStateException("Malformed DN: " + dn);
    }

    b2 = chars[position + 1];
    if (b2 >= '0' && b2 <= '9') {
      b2 = b2 - '0';
    } else if (b2 >= 'a' && b2 <= 'f') {
      b2 = b2 - 87; // 87 = 'a' - 10
    } else if (b2 >= 'A' && b2 <= 'F') {
      b2 = b2 - 55; // 55 = 'A' - 10
    } else {
      throw new IllegalStateException("Malformed DN: " + dn);
    }

    return (b1 << 4) + b2;
  }

  /**
   * Parses the DN and returns the most significant attribute value for an attribute type, or null
   * if none found.
   *
   * @param attributeType attribute type to look for (e.g. "ca")
   */
  public String findMostSpecific(String attributeType) {
    // Initialize internal state.
    pos = 0;
    beg = 0;
    end = 0;
    cur = 0;
    chars = dn.toCharArray();

    String attType = nextAT();
    if (attType == null) {
      return null;
    }
    while (true) {
      String attValue = "";

      if (pos == length) {
        return null;
      }

      switch (chars[pos]) {
        case '"':
          attValue = quotedAV();
          break;
        case '#':
          attValue = hexAV();
          break;
        case '+':
        case ',':
        case ';': // compatibility with RFC 1779: semicolon can separate RDNs
          //empty attribute value
          break;
        default:
          attValue = escapedAV();
      }

      // Values are ordered from most specific to least specific
      // due to the RFC2253 formatting. So take the first match
      // we see.
      if (attributeType.equalsIgnoreCase(attType)) {
        return attValue;
      }

      if (pos >= length) {
        return null;
      }

      if (chars[pos] == ',' || chars[pos] == ';') {
      } else if (chars[pos] != '+') {
        throw new IllegalStateException("Malformed DN: " + dn);
      }

      pos++;
      attType = nextAT();
      if (attType == null) {
        throw new IllegalStateException("Malformed DN: " + dn);
      }
    }
  }
}
