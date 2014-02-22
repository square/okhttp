/*
 * Copyright 2013 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal.spdy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import okio.ByteString;

/**
 * This class was originally composed from the following classes in
 * <a href="https://github.com/twitter/hpack">Twitter Hpack</a>.
 * <ul>
 * <li>{@code com.twitter.hpack.HuffmanEncoder}</li>
 * <li>{@code com.twitter.hpack.HuffmanDecoder}</li>
 * <li>{@code com.twitter.hpack.HpackUtil}</li>
 * </ul>
 */
class Huffman {
  enum Codec {
    REQUEST(REQUEST_CODES, REQUEST_CODE_LENGTHS),
    RESPONSE(RESPONSE_CODES, RESPONSE_CODE_LENGTHS);

    private final Node root = new Node();
    private final int[] codes;
    private final byte[] lengths;

    /**
     * @param codes Index designates the symbol this code represents.
     * @param lengths Index designates the symbol this code represents.
     */
    Codec(int[] codes, byte[] lengths) {
      buildTree(codes, lengths);
      this.codes = codes;
      this.lengths = lengths;
    }

    void encode(byte[] data, OutputStream out) throws IOException {
      long current = 0;
      int n = 0;

      for (int i = 0; i < data.length; i++) {
        int b = data[i] & 0xFF;
        int code = codes[b];
        int nbits = lengths[b];

        current <<= nbits;
        current |= code;
        n += nbits;

        while (n >= 8) {
          n -= 8;
          out.write(((int) (current >> n)));
        }
      }

      if (n > 0) {
        current <<= (8 - n);
        current |= (0xFF >>> n);
        out.write((int) current);
      }
    }

    int encodedLength(byte[] bytes) {
      long len = 0;

      for (int i = 0; i < bytes.length; i++) {
        int b = bytes[i] & 0xFF;
        len += lengths[b];
      }

      return (int) ((len + 7) >> 3);
    }

    ByteString decode(ByteString buf) throws IOException {
      return ByteString.of(decode(buf.toByteArray()));
    }

    byte[] decode(byte[] buf) throws IOException {
      // FIXME
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      Node node = root;
      int current = 0;
      int nbits = 0;
      for (int i = 0; i < buf.length; i++) {
        int b = buf[i] & 0xFF;
        current = (current << 8) | b;
        nbits += 8;
        while (nbits >= 8) {
          int c = (current >>> (nbits - 8)) & 0xFF;
          node = node.children[c];
          if (node.children == null) {
            // terminal node
            baos.write(node.symbol);
            nbits -= node.terminalBits;
            node = root;
          } else {
            // non-terminal node
            nbits -= 8;
          }
        }
      }

      while (nbits > 0) {
        int c = (current << (8 - nbits)) & 0xFF;
        node = node.children[c];
        if (node.children != null || node.terminalBits > nbits) {
          break;
        }
        baos.write(node.symbol);
        nbits -= node.terminalBits;
        node = root;
      }

      return baos.toByteArray();
    }

    private void buildTree(int[] codes, byte[] lengths) {
      for (int i = 0; i < lengths.length; i++) {
        addCode(i, codes[i], lengths[i]);
      }
    }

    private void addCode(int sym, int code, byte len) {
      Node terminal = new Node(sym, len);

      Node current = root;
      while (len > 8) {
        len -= 8;
        int i = ((code >>> len) & 0xFF);
        if (current.children == null) {
          throw new IllegalStateException("invalid dictionary: prefix not unique");
        }
        if (current.children[i] == null) {
          current.children[i] = new Node();
        }
        current = current.children[i];
      }

      int shift = 8 - len;
      int start = (code << shift) & 0xFF;
      int end = 1 << shift;
      for (int i = start; i < start + end; i++) {
        current.children[i] = terminal;
      }
    }
  }

  private static final class Node {

    // Null if terminal.
    private final Node[] children;

    // Terminal nodes have a symbol.
    private final int symbol;

    // Number of bits represented in the terminal node.
    private final int terminalBits;

    /** Construct an internal node. */
    Node() {
      this.children = new Node[256];
      this.symbol = 0; // Not read.
      this.terminalBits = 0; // Not read.
    }

    /**
     * Construct a terminal node.
     *
     * @param symbol symbol the node represents
     * @param bits length of Huffman code in bits
     */
    Node(int symbol, int bits) {
      this.children = null;
      this.symbol = symbol;
      int b = bits & 0x07;
      this.terminalBits = b == 0 ? 8 : b;
    }
  }

  // Appendix C: Huffman Codes For Requests
  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-C
  private static final int[] REQUEST_CODES = {
      0x7ffffba, 0x7ffffbb, 0x7ffffbc, 0x7ffffbd, 0x7ffffbe, 0x7ffffbf, 0x7ffffc0, 0x7ffffc1,
      0x7ffffc2, 0x7ffffc3, 0x7ffffc4, 0x7ffffc5, 0x7ffffc6, 0x7ffffc7, 0x7ffffc8, 0x7ffffc9,
      0x7ffffca, 0x7ffffcb, 0x7ffffcc, 0x7ffffcd, 0x7ffffce, 0x7ffffcf, 0x7ffffd0, 0x7ffffd1,
      0x7ffffd2, 0x7ffffd3, 0x7ffffd4, 0x7ffffd5, 0x7ffffd6, 0x7ffffd7, 0x7ffffd8, 0x7ffffd9, 0xe8,
      0xffc, 0x3ffa, 0x7ffc, 0x7ffd, 0x24, 0x6e, 0x7ffe, 0x7fa, 0x7fb, 0x3fa, 0x7fc, 0xe9, 0x25,
      0x4, 0x0, 0x5, 0x6, 0x7, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x2b, 0x2c, 0x1ec, 0xea, 0x3fffe, 0x2d,
      0x1fffc, 0x1ed, 0x3ffb, 0x6f, 0xeb, 0xec, 0xed, 0xee, 0x70, 0x1ee, 0x1ef, 0x1f0, 0x1f1, 0x3fb,
      0x1f2, 0xef, 0x1f3, 0x1f4, 0x1f5, 0x1f6, 0x1f7, 0xf0, 0xf1, 0x1f8, 0x1f9, 0x1fa, 0x1fb, 0x1fc,
      0x3fc, 0x3ffc, 0x7ffffda, 0x1ffc, 0x3ffd, 0x2e, 0x7fffe, 0x8, 0x2f, 0x9, 0x30, 0x1, 0x31,
      0x32, 0x33, 0xa, 0x71, 0x72, 0xb, 0x34, 0xc, 0xd, 0xe, 0xf2, 0xf, 0x10, 0x11, 0x35, 0x73,
      0x36, 0xf3, 0xf4, 0xf5, 0x1fffd, 0x7fd, 0x1fffe, 0xffd, 0x7ffffdb, 0x7ffffdc, 0x7ffffdd,
      0x7ffffde, 0x7ffffdf, 0x7ffffe0, 0x7ffffe1, 0x7ffffe2, 0x7ffffe3, 0x7ffffe4, 0x7ffffe5,
      0x7ffffe6, 0x7ffffe7, 0x7ffffe8, 0x7ffffe9, 0x7ffffea, 0x7ffffeb, 0x7ffffec, 0x7ffffed,
      0x7ffffee, 0x7ffffef, 0x7fffff0, 0x7fffff1, 0x7fffff2, 0x7fffff3, 0x7fffff4, 0x7fffff5,
      0x7fffff6, 0x7fffff7, 0x7fffff8, 0x7fffff9, 0x7fffffa, 0x7fffffb, 0x7fffffc, 0x7fffffd,
      0x7fffffe, 0x7ffffff, 0x3ffff80, 0x3ffff81, 0x3ffff82, 0x3ffff83, 0x3ffff84, 0x3ffff85,
      0x3ffff86, 0x3ffff87, 0x3ffff88, 0x3ffff89, 0x3ffff8a, 0x3ffff8b, 0x3ffff8c, 0x3ffff8d,
      0x3ffff8e, 0x3ffff8f, 0x3ffff90, 0x3ffff91, 0x3ffff92, 0x3ffff93, 0x3ffff94, 0x3ffff95,
      0x3ffff96, 0x3ffff97, 0x3ffff98, 0x3ffff99, 0x3ffff9a, 0x3ffff9b, 0x3ffff9c, 0x3ffff9d,
      0x3ffff9e, 0x3ffff9f, 0x3ffffa0, 0x3ffffa1, 0x3ffffa2, 0x3ffffa3, 0x3ffffa4, 0x3ffffa5,
      0x3ffffa6, 0x3ffffa7, 0x3ffffa8, 0x3ffffa9, 0x3ffffaa, 0x3ffffab, 0x3ffffac, 0x3ffffad,
      0x3ffffae, 0x3ffffaf, 0x3ffffb0, 0x3ffffb1, 0x3ffffb2, 0x3ffffb3, 0x3ffffb4, 0x3ffffb5,
      0x3ffffb6, 0x3ffffb7, 0x3ffffb8, 0x3ffffb9, 0x3ffffba, 0x3ffffbb, 0x3ffffbc, 0x3ffffbd,
      0x3ffffbe, 0x3ffffbf, 0x3ffffc0, 0x3ffffc1, 0x3ffffc2, 0x3ffffc3, 0x3ffffc4, 0x3ffffc5,
      0x3ffffc6, 0x3ffffc7, 0x3ffffc8, 0x3ffffc9, 0x3ffffca, 0x3ffffcb, 0x3ffffcc, 0x3ffffcd,
      0x3ffffce, 0x3ffffcf, 0x3ffffd0, 0x3ffffd1, 0x3ffffd2, 0x3ffffd3, 0x3ffffd4, 0x3ffffd5,
      0x3ffffd6, 0x3ffffd7, 0x3ffffd8, 0x3ffffd9, 0x3ffffda, 0x3ffffdb
  };

  private static final byte[] REQUEST_CODE_LENGTHS = {
      27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27,
      27, 27, 27, 27, 27, 27, 27, 27, 27, 8, 12, 14, 15, 15, 6, 7, 15, 11, 11, 10, 11, 8, 6, 5, 4,
      5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 9, 8, 18, 6, 17, 9, 14, 7, 8, 8, 8, 8, 7, 9, 9, 9, 9, 10, 9, 8,
      9, 9, 9, 9, 9, 8, 8, 9, 9, 9, 9, 9, 10, 14, 27, 13, 14, 6, 19, 5, 6, 5, 6, 4, 6, 6, 6, 5, 7,
      7, 5, 6, 5, 5, 5, 8, 5, 5, 5, 6, 7, 6, 8, 8, 8, 17, 11, 17, 12, 27, 27, 27, 27, 27, 27, 27,
      27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27, 27,
      27, 27, 27, 27, 27, 27, 27, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26,
      26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26,
      26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26,
      26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26,
      26, 26, 26, 26, 26, 26, 26
  };

  // Appendix D: Huffman Codes For Responses
  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-05#appendix-D
  private static final int[] RESPONSE_CODES = {
      0x1ffffbc, 0x1ffffbd, 0x1ffffbe, 0x1ffffbf, 0x1ffffc0, 0x1ffffc1, 0x1ffffc2, 0x1ffffc3,
      0x1ffffc4, 0x1ffffc5, 0x1ffffc6, 0x1ffffc7, 0x1ffffc8, 0x1ffffc9, 0x1ffffca, 0x1ffffcb,
      0x1ffffcc, 0x1ffffcd, 0x1ffffce, 0x1ffffcf, 0x1ffffd0, 0x1ffffd1, 0x1ffffd2, 0x1ffffd3,
      0x1ffffd4, 0x1ffffd5, 0x1ffffd6, 0x1ffffd7, 0x1ffffd8, 0x1ffffd9, 0x1ffffda, 0x1ffffdb, 0x0,
      0xffa, 0x6a, 0x1ffa, 0x3ffc, 0x1ec, 0x3f8, 0x1ffb, 0x1ed, 0x1ee, 0xffb, 0x7fa, 0x22, 0x23,
      0x24, 0x6b, 0x1, 0x2, 0x3, 0x8, 0x9, 0xa, 0x25, 0x26, 0xb, 0xc, 0xd, 0x1ef, 0xfffa, 0x6c,
      0x1ffc, 0xffc, 0xfffb, 0x6d, 0xea, 0xeb, 0xec, 0xed, 0xee, 0x27, 0x1f0, 0xef, 0xf0, 0x3f9,
      0x1f1, 0x28, 0xf1, 0xf2, 0x1f2, 0x3fa, 0x1f3, 0x29, 0xe, 0x1f4, 0x1f5, 0xf3, 0x3fb, 0x1f6,
      0x3fc, 0x7fb, 0x1ffd, 0x7fc, 0x7ffc, 0x1f7, 0x1fffe, 0xf, 0x6e, 0x2a, 0x2b, 0x10, 0x6f, 0x70,
      0x71, 0x2c, 0x1f8, 0x1f9, 0x72, 0x2d, 0x2e, 0x2f, 0x30, 0x1fa, 0x31, 0x32, 0x33, 0x34, 0x73,
      0xf4, 0x74, 0xf5, 0x1fb, 0xfffc, 0x3ffd, 0xfffd, 0xfffe, 0x1ffffdc, 0x1ffffdd, 0x1ffffde,
      0x1ffffdf, 0x1ffffe0, 0x1ffffe1, 0x1ffffe2, 0x1ffffe3, 0x1ffffe4, 0x1ffffe5, 0x1ffffe6,
      0x1ffffe7, 0x1ffffe8, 0x1ffffe9, 0x1ffffea, 0x1ffffeb, 0x1ffffec, 0x1ffffed, 0x1ffffee,
      0x1ffffef, 0x1fffff0, 0x1fffff1, 0x1fffff2, 0x1fffff3, 0x1fffff4, 0x1fffff5, 0x1fffff6,
      0x1fffff7, 0x1fffff8, 0x1fffff9, 0x1fffffa, 0x1fffffb, 0x1fffffc, 0x1fffffd, 0x1fffffe,
      0x1ffffff, 0xffff80, 0xffff81, 0xffff82, 0xffff83, 0xffff84, 0xffff85, 0xffff86, 0xffff87,
      0xffff88, 0xffff89, 0xffff8a, 0xffff8b, 0xffff8c, 0xffff8d, 0xffff8e, 0xffff8f, 0xffff90,
      0xffff91, 0xffff92, 0xffff93, 0xffff94, 0xffff95, 0xffff96, 0xffff97, 0xffff98, 0xffff99,
      0xffff9a, 0xffff9b, 0xffff9c, 0xffff9d, 0xffff9e, 0xffff9f, 0xffffa0, 0xffffa1, 0xffffa2,
      0xffffa3, 0xffffa4, 0xffffa5, 0xffffa6, 0xffffa7, 0xffffa8, 0xffffa9, 0xffffaa, 0xffffab,
      0xffffac, 0xffffad, 0xffffae, 0xffffaf, 0xffffb0, 0xffffb1, 0xffffb2, 0xffffb3, 0xffffb4,
      0xffffb5, 0xffffb6, 0xffffb7, 0xffffb8, 0xffffb9, 0xffffba, 0xffffbb, 0xffffbc, 0xffffbd,
      0xffffbe, 0xffffbf, 0xffffc0, 0xffffc1, 0xffffc2, 0xffffc3, 0xffffc4, 0xffffc5, 0xffffc6,
      0xffffc7, 0xffffc8, 0xffffc9, 0xffffca, 0xffffcb, 0xffffcc, 0xffffcd, 0xffffce, 0xffffcf,
      0xffffd0, 0xffffd1, 0xffffd2, 0xffffd3, 0xffffd4, 0xffffd5, 0xffffd6, 0xffffd7, 0xffffd8,
      0xffffd9, 0xffffda, 0xffffdb, 0xffffdc
  };

  private static final byte[] RESPONSE_CODE_LENGTHS = {
      25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
      25, 25, 25, 25, 25, 25, 25, 25, 25, 4, 12, 7, 13, 14, 9, 10, 13, 9, 9, 12, 11, 6, 6, 6, 7, 4,
      4, 4, 5, 5, 5, 6, 6, 5, 5, 5, 9, 16, 7, 13, 12, 16, 7, 8, 8, 8, 8, 8, 6, 9, 8, 8, 10, 9, 6, 8,
      8, 9, 10, 9, 6, 5, 9, 9, 8, 10, 9, 10, 11, 13, 11, 15, 9, 17, 5, 7, 6, 6, 5, 7, 7, 7, 6, 9, 9,
      7, 6, 6, 6, 6, 9, 6, 6, 6, 6, 7, 8, 7, 8, 9, 16, 14, 16, 16, 25, 25, 25, 25, 25, 25, 25, 25,
      25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
      25, 25, 25, 25, 25, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
      24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
      24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
      24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24, 24,
      24, 24, 24, 24, 24, 24
  };
}
