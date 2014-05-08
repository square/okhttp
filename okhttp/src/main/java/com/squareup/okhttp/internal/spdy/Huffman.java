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

  // Appendix C: Huffman Codes
  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-07#appendix-C
  private static final int[] CODES = {
      0x3ffffba, 0x3ffffbb, 0x3ffffbc, 0x3ffffbd, 0x3ffffbe, 0x3ffffbf, 0x3ffffc0, 0x3ffffc1,
      0x3ffffc2, 0x3ffffc3, 0x3ffffc4, 0x3ffffc5, 0x3ffffc6, 0x3ffffc7, 0x3ffffc8, 0x3ffffc9,
      0x3ffffca, 0x3ffffcb, 0x3ffffcc, 0x3ffffcd, 0x3ffffce, 0x3ffffcf, 0x3ffffd0, 0x3ffffd1,
      0x3ffffd2, 0x3ffffd3, 0x3ffffd4, 0x3ffffd5, 0x3ffffd6, 0x3ffffd7, 0x3ffffd8, 0x3ffffd9, 0x6,
      0x1ffc, 0x1f0, 0x3ffc, 0x7ffc, 0x1e, 0x64, 0x1ffd, 0x3fa, 0x1f1, 0x3fb, 0x3fc, 0x65, 0x66,
      0x1f, 0x7, 0x0, 0x1, 0x2, 0x8, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0xec, 0x1fffc, 0x27,
      0x7ffd, 0x3fd, 0x7ffe, 0x67, 0xed, 0xee, 0x68, 0xef, 0x69, 0x6a, 0x1f2, 0xf0, 0x1f3, 0x1f4,
      0x1f5, 0x6b, 0x6c, 0xf1, 0xf2, 0x1f6, 0x1f7, 0x6d, 0x28, 0xf3, 0x1f8, 0x1f9, 0xf4, 0x1fa,
      0x1fb, 0x7fc, 0x3ffffda, 0x7fd, 0x3ffd, 0x6e, 0x3fffe, 0x9, 0x6f, 0xa, 0x29, 0xb, 0x70, 0x2a,
      0x2b, 0xc, 0xf5, 0xf6, 0x2c, 0x2d, 0x2e, 0xd, 0x2f, 0x1fc, 0x30, 0x31, 0xe, 0x71, 0x72, 0x73,
      0x74, 0x75, 0xf7, 0x1fffd, 0xffc, 0x1fffe, 0xffd, 0x3ffffdb, 0x3ffffdc, 0x3ffffdd, 0x3ffffde,
      0x3ffffdf, 0x3ffffe0, 0x3ffffe1, 0x3ffffe2, 0x3ffffe3, 0x3ffffe4, 0x3ffffe5, 0x3ffffe6,
      0x3ffffe7, 0x3ffffe8, 0x3ffffe9, 0x3ffffea, 0x3ffffeb, 0x3ffffec, 0x3ffffed, 0x3ffffee,
      0x3ffffef, 0x3fffff0, 0x3fffff1, 0x3fffff2, 0x3fffff3, 0x3fffff4, 0x3fffff5, 0x3fffff6,
      0x3fffff7, 0x3fffff8, 0x3fffff9, 0x3fffffa, 0x3fffffb, 0x3fffffc, 0x3fffffd, 0x3fffffe,
      0x3ffffff, 0x1ffff80, 0x1ffff81, 0x1ffff82, 0x1ffff83, 0x1ffff84, 0x1ffff85, 0x1ffff86,
      0x1ffff87, 0x1ffff88, 0x1ffff89, 0x1ffff8a, 0x1ffff8b, 0x1ffff8c, 0x1ffff8d, 0x1ffff8e,
      0x1ffff8f, 0x1ffff90, 0x1ffff91, 0x1ffff92, 0x1ffff93, 0x1ffff94, 0x1ffff95, 0x1ffff96,
      0x1ffff97, 0x1ffff98, 0x1ffff99, 0x1ffff9a, 0x1ffff9b, 0x1ffff9c, 0x1ffff9d, 0x1ffff9e,
      0x1ffff9f, 0x1ffffa0, 0x1ffffa1, 0x1ffffa2, 0x1ffffa3, 0x1ffffa4, 0x1ffffa5, 0x1ffffa6,
      0x1ffffa7, 0x1ffffa8, 0x1ffffa9, 0x1ffffaa, 0x1ffffab, 0x1ffffac, 0x1ffffad, 0x1ffffae,
      0x1ffffaf, 0x1ffffb0, 0x1ffffb1, 0x1ffffb2, 0x1ffffb3, 0x1ffffb4, 0x1ffffb5, 0x1ffffb6,
      0x1ffffb7, 0x1ffffb8, 0x1ffffb9, 0x1ffffba, 0x1ffffbb, 0x1ffffbc, 0x1ffffbd, 0x1ffffbe,
      0x1ffffbf, 0x1ffffc0, 0x1ffffc1, 0x1ffffc2, 0x1ffffc3, 0x1ffffc4, 0x1ffffc5, 0x1ffffc6,
      0x1ffffc7, 0x1ffffc8, 0x1ffffc9, 0x1ffffca, 0x1ffffcb, 0x1ffffcc, 0x1ffffcd, 0x1ffffce,
      0x1ffffcf, 0x1ffffd0, 0x1ffffd1, 0x1ffffd2, 0x1ffffd3, 0x1ffffd4, 0x1ffffd5, 0x1ffffd6,
      0x1ffffd7, 0x1ffffd8, 0x1ffffd9, 0x1ffffda, 0x1ffffdb
  };

  private static final byte[] CODE_LENGTHS = {
      26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26,
      26, 26, 26, 26, 26, 26, 26, 26, 26, 5, 13, 9, 14, 15, 6, 7, 13, 10, 9, 10, 10, 7, 7, 6, 5, 4,
      4, 4, 5, 6, 6, 6, 6, 6, 6, 6, 8, 17, 6, 15, 10, 15, 7, 8, 8, 7, 8, 7, 7, 9, 8, 9, 9, 9, 7, 7,
      8, 8, 9, 9, 7, 6, 8, 9, 9, 8, 9, 9, 11, 26, 11, 14, 7, 18, 5, 7, 5, 6, 5, 7, 6, 6, 5, 8, 8, 6,
      6, 6, 5, 6, 9, 6, 6, 5, 7, 7, 7, 7, 7, 8, 17, 12, 17, 12, 26, 26, 26, 26, 26, 26, 26, 26, 26,
      26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26, 26,
      26, 26, 26, 26, 26, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
      25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
      25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
      25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
      25, 25, 25, 25, 25
  };

  private static final Huffman INSTANCE = new Huffman();

  public static Huffman get() {
    return INSTANCE;
  }

  private final Node root = new Node();

  private Huffman() {
    buildTree();
  }

  void encode(byte[] data, OutputStream out) throws IOException {
    long current = 0;
    int n = 0;

    for (int i = 0; i < data.length; i++) {
      int b = data[i] & 0xFF;
      int code = CODES[b];
      int nbits = CODE_LENGTHS[b];

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
      len += CODE_LENGTHS[b];
    }

    return (int) ((len + 7) >> 3);
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

  private void buildTree() {
    for (int i = 0; i < CODE_LENGTHS.length; i++) {
      addCode(i, CODES[i], CODE_LENGTHS[i]);
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
}
