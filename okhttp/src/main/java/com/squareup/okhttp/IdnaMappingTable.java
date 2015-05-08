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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

/**
 * The <a href="http://www.unicode.org/reports/tr46/#IDNA_Mapping_Table">IDNA mapping table</a>
 * maps ~1.1 million code points to their domain name counterparts. There are a few main ways
 * that characters are mapped:
 * <ul>
 *   <li>Identity mapping. For example, "square.com" is unchanged by the table.
 *   <li>Ignored code points. For example, U+FEFF is discarded if present, so "square\ufeff.com."
 *       yields "square.com".
 *   <li>Mapped code points. This includes case mapping, like "SQUARE.COM" to "square.com" and
 *       "Σ.com" to "σ.com". Plus more exotic mappings such as for ℡ — the telephone symbol — which
 *       maps to three code points "tel".
 *   <li>Disallowed code points. The cyrillic letter palochka "Ӏ" is disallowed, and mapping
 *       hostnames that contain it will return null.
 * </ul>
 *
 * <h3>Implementation Notes</h3>
 *
 * <p>The IDNA table is specified as a 768 KiB human-readable text file. In the OkHttp .jar file we
 * ship a smaller 46 KIB machine-readable binary file. The two formats are functionally equivalent,
 * and this class has APIs to read both.
 *
 * <p>This class attempts to balance memory and runtime efficiency, both when it reads the IDNA
 * binary file and when using the resulting tables to map code points. Data is modeled as ranges of
 * code points: a range like [U+0061..U+007A] is stored as a single element in each of two unsigned
 * short arrays. To look up the value for U+0062, we binary search to find the entry for U+0061, and
 * look up the value shared for that entire range.
 *
 * <p>Since code points are 21-bit numbers (0x0..10ffff), we split them into pages that each contain
 * a 16 bit range. For example, values in 0x30000..0x3ffff are in page 0x3 which is then binary
 * searched over a 16 bit suffix: between 0x0000..0xffff. We save ~16 KiB of memory using unsigned
 * shorts instead of ints as the subjects of our binary search.
 *
 * <p>The value of each range is also an unsigned short, containing a status (valid, ignored,
 * mapped, etc.) and either an IDNA2008 status (NV8 or XV8) or an offset to look up the replacement
 * string. All replacement strings are concatenated substrings of a shared ~8,600 character string.
 *
 * <p>This class uses bit packing to store several values in a single unsigned short. There are two
 * bit packing choices:
 * <ul>
 *   <li>Ranges with status "mapped" or "disallowed_STD3_mapped" use 2 bits for the bit packing type
 *       and status, plus 14 bits for the offset of the mapped string.
 *   <li>Other ranges use 2 bits for the bit packing type, 6 bits that are unused, 4 bits for IDNA
 *       2008 status and 4 bits for status.
 * </ul>
 *
 * <p>This documentation refers to "unsigned short". Java's unsigned short type is named 'char',
 * but none of the arrays in this class contain UTF-16 code points and should not be interpreted
 * as text.
 */
final class IdnaMappingTable {
  private static final BitPackingType[] BIT_PACKING_TYPE_VALUES = BitPackingType.values();
  private static final Status[] STATUS_VALUES = Status.values();
  private static final Idna2008Status[] IDNA_2008_STATUS_VALUES = Idna2008Status.values();
  public static final IdnaMappingTable INSTANCE;
  static {
    try {
      BufferedSource source = Okio.buffer(Okio.source(
          IdnaMappingTable.class.getResourceAsStream("/IdnaMappingTable.bin")));
      INSTANCE = IdnaMappingTable.readBinary(source);
    } catch (IOException e) {
      throw new RuntimeException("Failed to initialize IDNA mapping table.", e);
    }
  }

  private final char[] pageOffsets;
  private final char[] rangeStarts;
  private final char[] rangeValues;
  private final String replacements;

  private IdnaMappingTable(
      char[] pageOffsets, char[] rangeStarts, char[] rangeValues, String replacements) {
    this.pageOffsets = pageOffsets;
    this.rangeStarts = rangeStarts;
    this.rangeValues = rangeValues;
    this.replacements = replacements;
  }

  /** Write this table to {@code out}. */
  public void writeBinary(BufferedSink out) throws IOException {
    out.writeInt(pageOffsets.length);
    for (char c : pageOffsets) {
      out.writeShort(c);
    }

    out.writeInt(rangeStarts.length);
    for (char c : rangeStarts) {
      out.writeShort(c);
    }
    for (char c : rangeValues) {
      out.writeShort(c);
    }

    Buffer replacementsUtf8 = new Buffer().writeUtf8(replacements);
    out.writeInt((int) replacementsUtf8.size());
    out.write(replacementsUtf8, replacementsUtf8.size());
  }

  /** Read a table from {@code in}. */
  public static IdnaMappingTable readBinary(BufferedSource in) throws IOException {
    int pageOffsetsLength = in.readInt();
    char[] pageOffsets = new char[pageOffsetsLength];
    for (int i = 0; i < pageOffsetsLength; i++) {
      pageOffsets[i] = (char) in.readShort();
    }

    int rangesLength = in.readInt();
    char[] rangeStarts = new char[rangesLength];
    for (int i = 0; i < rangesLength; i++) {
      rangeStarts[i] = (char) in.readShort();
    }
    char[] rangeValues = new char[rangesLength];
    for (int i = 0; i < rangesLength; i++) {
      rangeValues[i] = (char) in.readShort();
    }

    int replacementsLength = in.readInt();
    String replacements = in.readUtf8(replacementsLength);
    return new IdnaMappingTable(pageOffsets, rangeStarts, rangeValues, replacements);
  }

  /**
   * TODO(jwilson): complete implementation of the processing rules.
   * http://www.unicode.org/reports/tr46/#Processing
   */
  String process(
      String domainName, boolean useStandard3AsciiRules, boolean transitionalProcessing) {
    Buffer result = new Buffer();

    int codePoint;
    for (int i = 0, size = domainName.length(); i < size; i += Character.charCount(codePoint)) {
      codePoint = domainName.codePointAt(i);
      char value = rangeValue(codePoint);
      BitPackingType bitPackingType = BIT_PACKING_TYPE_VALUES[(value >>> 14)];

      Status status;
      Idna2008Status idna2008Status;
      switch (bitPackingType) {
        case mapped:
          status = Status.mapped;
          idna2008Status = Idna2008Status.NONE;
          break;

        case disallowed_STD3_mapped:
          status = Status.disallowed_STD3_mapped;
          idna2008Status = Idna2008Status.NONE;
          break;

        case other:
          status = STATUS_VALUES[value & 0x0f];
          idna2008Status = IDNA_2008_STATUS_VALUES[(value >>> 4) & 0xf];
          break;

        default:
          throw new AssertionError();
      }

      if (idna2008Status != Idna2008Status.NONE) {
        throw new UnsupportedOperationException("TODO");
      }

      switch (status) {
        case valid:
          result.writeUtf8CodePoint(codePoint);
          break;

        case ignored:
          break;

        case disallowed:
          return null;

        case mapped:
          int replacementOffset = value & 0x3fff;
          appendReplacement(result, replacementOffset);
          break;

        case deviation:
        case disallowed_STD3_valid:
        case disallowed_STD3_mapped:
          throw new UnsupportedOperationException("TODO");

      }
    }

    return result.readUtf8();
  }

  /** Binary search for the unsigned short that describes how to handle {@code codePoint}. */
  private char rangeValue(int codePoint) {
    int page = codePoint >>> 16;
    int start = pageOffsets[page];
    int end = pageOffsets[page + 1];
    int rangeIndex = Arrays.binarySearch(rangeStarts, start, end, (char) (codePoint & 0xffff));
    if (rangeIndex < 0) {
      rangeIndex = -rangeIndex - 2;
    }
    return rangeValues[rangeIndex];
  }

  /** Lookup the replacement string at {@code replacementOffset} and emit it to {@code result}. */
  private void appendReplacement(Buffer result, int replacementOffset) {
    int replacementCodePoint;
    for (int i = replacementOffset; ; i += Character.charCount(replacementCodePoint)) {
      replacementCodePoint = replacements.codePointAt(i);
      if (replacementCodePoint == '\u0000') return;
      result.writeUtf8CodePoint(replacementCodePoint);
    }
  }

  /**
   * Parse a table from a semicolon-delimited text file. Each line contains a range of code points,
   * a status, an optional sequence of replacement codepoints, and an optional IDNA 2008 status.
   *
   * <pre>{@code
   *
   *   0000..002C    ; disallowed                    #  NULL..COMMA
   *   002D          ; valid                         #  HYPHEN-MINUS
   *   ...
   *   0041          ; mapped       ; 0061           #  LATIN CAPITAL LETTER A
   *   ...
   *   00A1..00A7    ; valid        ;      ; NV8     #  INVERTED EXCLAMATION MARK..SECTION SIGN
   *   00AD          ; ignored                       #  SOFT HYPHEN
   *   ...
   *   00DF          ; deviation    ; 0073 0073      #  LATIN SMALL LETTER SHARP S
   *   ...
   *   19DA          ; valid        ;      ; XV8     # 5.2  NEW TAI LUE THAM DIGIT ONE
   *   ...
   * }</pre>
   */
  static IdnaMappingTable readText(BufferedSource source) throws IOException {
    List<Range> ranges = new ArrayList<>();
    for (String line; (line = source.readUtf8Line()) != null; ) {
      // Drop trailing comment.
      int hash = line.indexOf('#');
      if (hash != -1) line = line.substring(0, hash);

      // Split on `;` and trim each part.
      String[] parts = line.split(";", -1);
      if (parts.length == 1) continue; // Empty row.
      for (int i = 0; i < parts.length; i++) {
        parts[i] = parts[i].trim();
      }

      Range range = new Range();

      // Read a character like "005A" or a character range like "005B..0060".
      int dotDot = parts[0].indexOf("..");
      if (dotDot != -1) {
        range.start = Integer.parseInt(parts[0].substring(0, dotDot), 16);
        range.end = Integer.parseInt(parts[0].substring(dotDot + 2), 16);
      } else {
        range.start = range.end = Integer.parseInt(parts[0], 16);
      }

      // Read a status like "mapped" or "disallowed_STD3_mapped".
      range.status = Status.valueOf(parts[1]);

      // Read a sequence of code mapped code points like "0031 2044 0034". Possibly empty.
      if (parts.length > 2 && !parts[2].isEmpty()) {
        String[] hexCodePoints = parts[2].split("\\s+");
        int[] codePoints = new int[hexCodePoints.length];
        for (int i = 0; i < hexCodePoints.length; i++) {
          codePoints[i] = Integer.parseInt(hexCodePoints[i], 16);
        }
        range.replacement = new String(codePoints, 0, codePoints.length);
      }

      // Read an IDNA 2008 status like "NV8" or "XV8"
      if (parts.length > 3) {
        range.idna2008Status = Idna2008Status.valueOf(parts[3]);
      }

      ranges.add(range);
    }
    return fromRanges(ranges);
  }

  /** Convert ranges from the text file to something more memory-efficient. */
  static IdnaMappingTable fromRanges(List<Range> ranges) {
    // 0x000000..0x10ffff is 17 pages, plus one to track the total size.
    char[] pageOffsets = new char[18];
    char[] rangeStarts = new char[ranges.size()];
    char[] rangeValues = new char[ranges.size()];
    StringBuilder replacements = new StringBuilder();

    // Reading the ranges backwards yields slightly more reuse in the replacement string.
    for (int r = ranges.size() - 1; r >= 0; r--) {
      Range range = ranges.get(r);
      if ((range.start & 0xffff) == 0) {
        int page = range.start >>> 16;
        pageOffsets[page] = (char) r;
      }

      rangeStarts[r] = (char) range.start; // Truncating cast.

      BitPackingType bitPackingType;
      switch (range.status) {
        case mapped:
          bitPackingType = BitPackingType.mapped;
          break;
        case disallowed_STD3_mapped:
          bitPackingType = BitPackingType.disallowed_STD3_mapped;
          break;
        default:
          bitPackingType = BitPackingType.other;
          break;
      }

      int value = bitPackingType.ordinal() << 14;
      switch (bitPackingType) {
        case mapped:
        case disallowed_STD3_mapped:
          String replacementPlusTerminator = range.replacement + "\u0000";
          int replacementOffset = replacements.indexOf(replacementPlusTerminator);
          if (replacementOffset == -1) {
            replacementOffset = replacements.length();
            replacements.append(replacementPlusTerminator);
          }
          value |= replacementOffset & 0x3fff;
          break;

        case other:
          value |= range.status.ordinal() & 0xf;
          value |= (range.idna2008Status.ordinal() << 4) & 0xf;
          break;
      }

      rangeValues[r] = (char) value;
    }
    pageOffsets[17] = (char) ranges.size();

    return new IdnaMappingTable(pageOffsets, rangeStarts, rangeValues, replacements.toString());
  }

  /** How we pack a status, offset, and IDNA 2008 status into 16 bits. */
  enum BitPackingType {
    mapped,
    disallowed_STD3_mapped,
    other
  }

  /** A range of code points that have the same rules. Code points are in [0..10ffff]. */
  static class Range {
    int start;
    int end;
    Status status;
    String replacement;
    Idna2008Status idna2008Status = Idna2008Status.NONE;
  }

  /** Code point status in the same format as the text file. */
  enum Status {
    valid,
    ignored,
    mapped,
    deviation,
    disallowed,
    disallowed_STD3_valid,
    disallowed_STD3_mapped
  }

  /** IDNA2008 status in the same format as the text file. NONE if no status is specified. */
  enum Idna2008Status {
    NONE,
    NV8,
    XV8
  }
}
