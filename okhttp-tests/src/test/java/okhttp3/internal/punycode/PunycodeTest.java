package okhttp3.internal.punycode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

import static java.lang.Character.UnicodeBlock.*;
import static okhttp3.internal.punycode.Punycode.isDisplaySafe;
import static okhttp3.internal.punycode.Punycode.safeScriptCombination;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PunycodeTest {
  @Test
  public void testValidCombinations() {
    assertTrue(safeScriptCombination(blocks(BASIC_LATIN)));
    assertTrue(safeScriptCombination(blocks(GREEK)));
    assertTrue(safeScriptCombination(blocks(CYRILLIC)));
    assertFalse(safeScriptCombination(blocks(CYRILLIC, BASIC_LATIN)));
    assertFalse(safeScriptCombination(blocks(CYRILLIC, GREEK)));
    assertFalse(safeScriptCombination(blocks(BASIC_LATIN, GREEK)));
    assertTrue(safeScriptCombination(blocks(ARABIC, BASIC_LATIN)));
    assertTrue(safeScriptCombination(blocks(HIRAGANA, BASIC_LATIN)));

    // TODO Handle Han combinations
    //assertTrue(safeScriptCombination(blocks(HAN, BOPOMOFO)));
    //assertTrue(safeScriptCombination(blocks(HAN, HIRAGANA, KATAKANA)));
    //assertTrue(safeScriptCombination(blocks(HAN, HANGUL)));
    //assertFalse(safeScriptCombination(blocks(BRAILLE, HANGUL)));
  }

  @Test
  public void testIsDisplaySafe() {
    assertTrue(isDisplaySafe("en.wikipedia.org"));
    assertFalse(isDisplaySafe("☃.net"));
    assertTrue(isDisplaySafe("她是這麼說的.net"));
    assertTrue(isDisplaySafe("правительство.рф"));
    assertTrue(isDisplaySafe("ακρόπολητώρα.org"));
    // Latin + Greek
    assertFalse(isDisplaySafe("wіkіреdіа.org"));
  }

  private Set<Character.UnicodeBlock> blocks(Character.UnicodeBlock... blocks) {
    return new LinkedHashSet<>(Arrays.asList(blocks));
  }
}
