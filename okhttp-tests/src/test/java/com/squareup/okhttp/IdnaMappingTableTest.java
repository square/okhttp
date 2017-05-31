/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.okhttp;

import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class IdnaMappingTableTest {
  @Test public void lowercaseCharactersMappedDirectly() throws Exception {
    assertEquals("abcd", IdnaMappingTable.INSTANCE.process("abcd", false, false));
    assertEquals("σ", IdnaMappingTable.INSTANCE.process("σ", false, false));
  }

  @Test public void uppercaseCharactersConvertedToLowercase() throws Exception {
    assertEquals("abcd", IdnaMappingTable.INSTANCE.process("ABCD", false, false));
    assertEquals("σ", IdnaMappingTable.INSTANCE.process("Σ", false, false));
  }

  @Test public void ignoredCharacters() throws Exception {
    // The soft hyphen (­) should be ignored.
    assertEquals("abcd", IdnaMappingTable.INSTANCE.process("AB\u00adCD", false, false));
  }

  @Test public void multipleCharacterMapping() throws Exception {
    // Map the single character telephone symbol (℡) to the string "tel".
    assertEquals("tel", IdnaMappingTable.INSTANCE.process("\u2121", false, false));
  }

  @Test public void mappingEdgeCases() throws Exception {
    // Check the last mapping, ignored character, and disallowed character in the table.
    assertEquals("\uD869\uDE00", IdnaMappingTable.INSTANCE.process("\uD87E\uDE1D", false, false));
    assertEquals("abcd", IdnaMappingTable.INSTANCE.process("ab\uDB40\uDDEFcd", false, false));
    assertEquals(null, IdnaMappingTable.INSTANCE.process("\uDBFF\uDFFF", false, false));
  }

  @Test public void readAndWriteTableFormats() throws Exception {
    // Read Unicode's easy-to-read, easy-to-parse 768 KiB file.
    BufferedSource textSource = Okio.buffer(Okio.source(
        IdnaMappingTableTest.class.getResourceAsStream("/IdnaMappingTable.txt")));
    IdnaMappingTable textTable = IdnaMappingTable.readText(textSource);

    // Read OkHttp's dense 46 KiB binary file.
    BufferedSource binarySource = Okio.buffer(Okio.source(
        IdnaMappingTableTest.class.getResourceAsStream("/IdnaMappingTable.bin")));
    IdnaMappingTable binaryTable = IdnaMappingTable.readBinary(binarySource);

    // Write both back down to binary to confirm that they're equal.
    Buffer textTableAsBuffer = new Buffer();
    textTable.writeBinary(textTableAsBuffer);
    Buffer binaryTableAsBuffer = new Buffer();
    binaryTable.writeBinary(binaryTableAsBuffer);
    assertEquals(binaryTableAsBuffer, textTableAsBuffer);
  }
}
