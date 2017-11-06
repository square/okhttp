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
  private Punycode() {
  }

  /**
   * Returns whether a given combination of scripts is considered safe given
   * an approximation of Chrome's rules.
   *
   * @param blocks the block combination in a single hostname section
   * @return whether the combination is considered safe.
   */
  public static boolean safeScriptCombination(Set<Character.UnicodeBlock> blocks) {
    if (blocks.size() == 1) {
      return true;
    }

    if (blocks.contains(Character.UnicodeBlock.BASIC_LATIN)) {
      if (blocks.contains(Character.UnicodeBlock.CYRILLIC)) {
        return false;
      }
      if (blocks.contains(Character.UnicodeBlock.GREEK)) {
        return false;
      }
      blocks.remove(Character.UnicodeBlock.BASIC_LATIN);
    }

    if (blocks.size() == 1) {
      return true;
    }

    // TODO check valid HAN combinations
    //if (scriptsInBlock.contains(Character.UnicodeScript.HAN)) {
    //  scriptsInBlock.remove(Character.UnicodeBlock.HAN);
    //
    //  if (scriptsInBlock.size() == 2) {
    //    if (scriptsInBlock.contains(Character.UnicodeBlock.HIRAGANA) && scriptsInBlock.contains(
    //        Character.UnicodeBlock.KATAKANA)) {
    //      return true;
    //    }
    //  } else if (scriptsInBlock.size() == 1) {
    //    if (scriptsInBlock.contains(Character.UnicodeBlock.HANGUL)) {
    //      return true;
    //    }
    //    // TODO should only be Han (CJK Ideographs)
    //    if (scriptsInBlock.contains(Character.UnicodeBlock.BOPOMOFO)) {
    //      return true;
    //    }
    //    if (scriptsInBlock.contains(Character.UnicodeBlock.HIRAGANA)) {
    //      return true;
    //    }
    //    if (scriptsInBlock.contains(Character.UnicodeBlock.KATAKANA)) {
    //      return true;
    //    }
    //  }
    //}

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
    Set<Character.UnicodeBlock> blocks = new LinkedHashSet<>();

    final int length = unicodeHost.length();
    for (int offset = 0; offset < length; ) {
      final int codepoint = unicodeHost.codePointAt(offset);

      if (codepoint == '.') {
        if (!safeScriptCombination(blocks)) {
          return false;
        }
        blocks.clear();
      } else {
        // confusable with / and .
        if (codepoint == '\u2027' || codepoint == '\u0338') {
          return false;
        }

        if (!Character.isUnicodeIdentifierPart(codepoint)) {
          return false;
        }

        blocks.add(Character.UnicodeBlock.of(codepoint));
      }

      offset += Character.charCount(codepoint);
    }

    if (!safeScriptCombination(blocks)) {
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
