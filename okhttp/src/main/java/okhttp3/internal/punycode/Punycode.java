/*
 * Copyright (C) 2017 Square, Inc.
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
package okhttp3.internal.punycode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Methods for safe handling of IDN characters in hostnames.
 *
 * @see <a href="https://www.chromium.org/developers/design-documents/idn-in-google-chrome">Chrome
 * Treatment</a>
 * @see <a href="https://wiki.mozilla.org/IDN_Display_Algorithm">Mozilla Treatment</a>
 */
public class Punycode {

  /**
   * Returns whether a given combination of scripts is considered safe given Chrome's rules.
   *
   * @param scriptsInBlock the script combination in a single block
   * @return whether the combination is considered safe.
   */
  public static boolean safeScriptCombination(Set<Character.UnicodeScript> scriptsInBlock) {
    if (scriptsInBlock.size() == 1) {
      return true;
    }

    if (scriptsInBlock.contains(Character.UnicodeScript.LATIN)) {
      if (scriptsInBlock.contains(Character.UnicodeScript.CYRILLIC)) {
        return false;
      }
      if (scriptsInBlock.contains(Character.UnicodeScript.GREEK)) {
        return false;
      }
      // TODO Should only be Latin (ascii)
      scriptsInBlock.remove(Character.UnicodeScript.LATIN);
    }

    if (scriptsInBlock.size() == 1) {
      return true;
    }

    if (scriptsInBlock.contains(Character.UnicodeScript.HAN)) {
      scriptsInBlock.remove(Character.UnicodeScript.HAN);

      if (scriptsInBlock.size() == 2) {
        if (scriptsInBlock.contains(Character.UnicodeScript.HIRAGANA) && scriptsInBlock.contains(
            Character.UnicodeScript.KATAKANA)) {
          return true;
        }
      } else if (scriptsInBlock.size() == 1) {
        if (scriptsInBlock.contains(Character.UnicodeScript.HANGUL)) {
          return true;
        }
        // TODO should only be Han (CJK Ideographs)
        if (scriptsInBlock.contains(Character.UnicodeScript.BOPOMOFO)) {
          return true;
        }
        if (scriptsInBlock.contains(Character.UnicodeScript.HIRAGANA)) {
          return true;
        }
        if (scriptsInBlock.contains(Character.UnicodeScript.KATAKANA)) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Returns whether a IDN host string is safe for showing to users without causing confusion.
   * Generally follows the Chrome approach.
   *
   * @param unicodeHost the host as a unicode string, potentially unsafe.
   * @return whether the host string is considered safe, i.e. is not designed to confuse users.
   */
  public static boolean isDisplaySafe(String unicodeHost) {
    Set<Character.UnicodeScript> scriptsInBlock = new LinkedHashSet<>();

    final int length = unicodeHost.length();
    for (int offset = 0; offset < length; ) {
      final int codepoint = unicodeHost.codePointAt(offset);

      if (codepoint == '.') {
        if (!safeScriptCombination(scriptsInBlock)) {
          return false;
        }
        scriptsInBlock.clear();
      } else {
        // confusable with / and .
        if (codepoint == '\u2027' || codepoint == '\u0338') {
          return false;
        }

        if (!Character.isUnicodeIdentifierPart(codepoint)) {
          return false;
        }

        scriptsInBlock.add(Character.UnicodeScript.of(codepoint));
      }

      offset += Character.charCount(codepoint);
    }

    if (!safeScriptCombination(scriptsInBlock)) {
      return false;
    }

    // TODO If two or more numbering systems (e.g. European digits + Bengali digits) are mixed,
    // punycode is shown.
    // TODO If there are any invisible characters (e.g. a sequence of the same combining mark or a
    // sequence of Kana combining marks), punycode is shown.
    // TODO Test the label for mixed script confusable per UTS 39. If mixed script confusable is
    // detected, show punycode.
    // TODO If a hostname belongs to an non-IDN TLD(top-level-domain) such as 'com', 'net', or 'uk'
    // and all the letters in a given label belong to a set of Cyrillic letters that look like Latin
    // letters (e.g. Cyrillic Small Letter IE - е  ), show punycode.
    // TODO If the label matches a dangerous pattern, punycode is shown.

    return true;
  }
}
