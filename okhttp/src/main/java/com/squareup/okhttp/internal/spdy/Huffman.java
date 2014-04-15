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

  // Appendix C: Huffman Codes
  // http://tools.ietf.org/html/draft-ietf-httpbis-header-compression-06#appendix-C
  private static final int[] CODES = {
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

  private static final byte[] CODE_LENGTHS = {
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
