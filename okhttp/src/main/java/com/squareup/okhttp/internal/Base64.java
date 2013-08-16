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

/**
 * @author Alexander Y. Kleymenov
 */

package com.squareup.okhttp.internal;

import java.io.UnsupportedEncodingException;

import static com.squareup.okhttp.internal.Util.EMPTY_BYTE_ARRAY;

/**
 * <a href="http://www.ietf.org/rfc/rfc2045.txt">Base64</a> encoder/decoder.
 * In violation of the RFC, this encoder doesn't wrap lines at 76 columns.
 */
public final class Base64 {
  private Base64() {
  }

  public static byte[] decode(byte[] in) {
    return decode(in, 0, in.length);
  }

  public static byte[] decode(byte[] in, int start, int len) {
    // number of padding characters ('=')
    int pad = 0;
    byte chr;
    // compute the number of the padding characters
    // and adjust the length of the input
    for (; ; len--) {
      if (len == 0) {
        // return an empty array if there's no padding, otherwise fail
        return (pad == 0) ? EMPTY_BYTE_ARRAY : null;
      }
      chr = in[start + len - 1];
      // skip the neutral characters
      if ((chr == '\n') || (chr == '\r') || (chr == ' ') || (chr == '\t')) {
        continue;
      }
      if (chr == '=') {
        pad++;
      } else {
        break;
      }
    }
    // calculate the length assuming there are no neutral characters, overestimate otherwise
    int length = len / 4 + (len + 1) / 2;
    // output array
    byte[] out = new byte[length];
    // decode into out
    int outIndex = decodeInternal(in, start, len, pad, out, 0);
    if (outIndex == -1) {
      return null;
    }
    if (outIndex == out.length) {
      return out;
    }
    // extract the result range
    byte[] result = new byte[outIndex];
    System.arraycopy(out, 0, result, 0, outIndex);
    return result;
  }

  public static int decodeInPlace(byte[] inOut, int start, int len) {
    // number of padding characters ('=')
    int pad = 0;
    byte chr;
    // compute the number of the padding characters
    // and adjust the length of the input
    for (; ; len--) {
      if (len == 0) {
        // return an empty array if there's no padding, otherwise fail
        return (pad == 0) ? 0 : -1;
      }
      chr = inOut[start + len - 1];
      // skip the neutral characters
      if ((chr == '\n') || (chr == '\r') || (chr == ' ') || (chr == '\t')) {
        continue;
      }
      if (chr == '=') {
        pad++;
      } else {
        break;
      }
    }
    // decode in-place
    return decodeInternal(inOut, start, len, pad, inOut, start);
  }

  private static int decodeInternal(byte[] in, int inStart, int len, int pad,
      byte[] out, int outStart) {
    byte chr;
    // index in the output array
    int outIndex = outStart;
    // index in the input array
    int inIndex = 0;
    // holds the value of the input character
    int bits = 0;
    // holds the value of the input quantum
    int quantum = 0;
    for (int i = 0; i < len; i++) {
      chr = in[inStart + i];
      // skip the neutral characters
      if ((chr == '\n') || (chr == '\r') || (chr == ' ') || (chr == '\t')) {
        continue;
      }
      if ((chr >= 'A') && (chr <= 'Z')) {
        // char ASCII value
        //  A    65    0
        //  Z    90    25 (ASCII - 65)
        bits = chr - 65;
      } else if ((chr >= 'a') && (chr <= 'z')) {
        // char ASCII value
        //  a    97    26
        //  z    122   51 (ASCII - 71)
        bits = chr - 71;
      } else if ((chr >= '0') && (chr <= '9')) {
        // char ASCII value
        //  0    48    52
        //  9    57    61 (ASCII + 4)
        bits = chr + 4;
      } else if (chr == '+') {
        bits = 62;
      } else if (chr == '/') {
        bits = 63;
      } else {
        return -1;
      }
      // append the value to the quantum
      quantum = (quantum << 6) | (byte) bits;
      if (inIndex % 4 == 3) {
        // 4 characters were read, so make the output:
        out[outIndex++] = (byte) (quantum >> 16);
        out[outIndex++] = (byte) (quantum >> 8);
        out[outIndex++] = (byte) quantum;
      }
      inIndex++;
    }
    // check that we have the right amount of padding
    if (((inIndex << 30) >> 30) + pad != 0) {
        return -1;
    }
    if (pad > 0) {
      // adjust the quantum value according to the padding
      quantum = quantum << (6 * pad);
      // make output
      out[outIndex++] = (byte) (quantum >> 16);
      if (pad == 1) {
        out[outIndex++] = (byte) (quantum >> 8);
      }
    }
    return outIndex - outStart;
  }

  private static final byte[] MAP = new byte[] {
      'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S',
      'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l',
      'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '0', '1', '2', '3', '4',
      '5', '6', '7', '8', '9', '+', '/'
  };

  public static String encode(byte[] in) {
    int length = (in.length + 2) * 4 / 3;
    byte[] out = new byte[length];
    int index = 0, end = in.length - in.length % 3;
    for (int i = 0; i < end; i += 3) {
      out[index++] = MAP[(in[i] & 0xff) >> 2];
      out[index++] = MAP[((in[i] & 0x03) << 4) | ((in[i + 1] & 0xff) >> 4)];
      out[index++] = MAP[((in[i + 1] & 0x0f) << 2) | ((in[i + 2] & 0xff) >> 6)];
      out[index++] = MAP[(in[i + 2] & 0x3f)];
    }
    switch (in.length % 3) {
      case 1:
        out[index++] = MAP[(in[end] & 0xff) >> 2];
        out[index++] = MAP[(in[end] & 0x03) << 4];
        out[index++] = '=';
        out[index++] = '=';
        break;
      case 2:
        out[index++] = MAP[(in[end] & 0xff) >> 2];
        out[index++] = MAP[((in[end] & 0x03) << 4) | ((in[end + 1] & 0xff) >> 4)];
        out[index++] = MAP[((in[end + 1] & 0x0f) << 2)];
        out[index++] = '=';
        break;
    }
    try {
      return new String(out, 0, index, "US-ASCII");
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }
}
