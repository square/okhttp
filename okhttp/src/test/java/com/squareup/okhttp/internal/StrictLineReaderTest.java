/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.squareup.okhttp.internal;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.US_ASCII;
import static org.junit.Assert.fail;

public final class StrictLineReaderTest {
  @Test public void lineReaderConsistencyWithReadAsciiLine() throws Exception {
    // Testing with LineReader buffer capacity 32 to check some corner cases.
    StrictLineReader lineReader = new StrictLineReader(createTestInputStream(), 32);
    InputStream refStream = createTestInputStream();
    while (true) {
      try {
        String refLine = Util.readAsciiLine(refStream);
        try {
          String line = lineReader.readLine();
          if (!refLine.equals(line)) {
            fail("line (\"" + line + "\") differs from expected (\"" + refLine + "\").");
          }
        } catch (EOFException eof) {
          fail("line reader threw EOFException too early.");
        }
      } catch (EOFException refEof) {
        try {
          lineReader.readLine();
          fail("line reader didn't throw the expected EOFException.");
        } catch (EOFException eof) {
          // OK
          break;
        }
      }
    }
    refStream.close();
    lineReader.close();
  }

  private InputStream createTestInputStream() {
    return new ByteArrayInputStream((
                /* each source lines below should represent 32 bytes, until the next comment */
        "12 byte line\n18 byte line......\n" +
            "pad\nline spanning two 32-byte bu" +
            "ffers\npad......................\n" +
            "pad\nline spanning three 32-byte " +
            "buffers and ending with LF at th" +
            "e end of a 32 byte buffer......\n" +
            "pad\nLine ending with CRLF split" +
            " at the end of a 32-byte buffer\r" +
            "\npad...........................\n" +
                        /* end of 32-byte lines */
            "line ending with CRLF\r\n" +
            "this is a long line with embedded CR \r ending with CRLF and having more than " +
            "32 characters\r\n" +
            "unterminated line - should be dropped").getBytes());
  }
}
