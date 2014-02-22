package okio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

class Util {
  public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  /** A cheap and type-safe constant for the UTF-8 Charset. */
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  private Util() {
  }

  public static void checkOffsetAndCount(long arrayLength, long offset, long count) {
    if ((offset | count) < 0 || offset > arrayLength || arrayLength - offset < count) {
      throw new ArrayIndexOutOfBoundsException();
    }
  }

  /**
   * Fills 'dst' with bytes from 'in', throwing EOFException if insufficient bytes are available.
   */
  public static void readFully(InputStream in, byte[] dst) throws IOException {
    readFully(in, dst, 0, dst.length);
  }

  /**
   * Reads exactly 'byteCount' bytes from 'in' (into 'dst' at offset 'offset'), and throws
   * EOFException if insufficient bytes are available.
   *
   * Used to implement {@link java.io.DataInputStream#readFully(byte[], int, int)}.
   */
  public static void readFully(InputStream in, byte[] dst, int offset, int byteCount)
      throws IOException {
    if (byteCount == 0) {
      return;
    }
    if (in == null) {
      throw new NullPointerException("in == null");
    }
    if (dst == null) {
      throw new NullPointerException("dst == null");
    }
    checkOffsetAndCount(dst.length, offset, byteCount);
    while (byteCount > 0) {
      int bytesRead = in.read(dst, offset, byteCount);
      if (bytesRead < 0) {
        throw new EOFException();
      }
      offset += bytesRead;
      byteCount -= bytesRead;
    }
  }

  public static int reverseBytesShort(short s) {
    int i = s & 0xffff;
    return (i & 0xff00) >>> 8
        |  (i & 0x00ff) << 8;
  }

  public static int reverseBytesInt(int i) {
    return (i & 0xff000000) >>> 24
        |  (i & 0x00ff0000) >>> 8
        |  (i & 0x0000ff00) << 8
        |  (i & 0x000000ff) << 24;
  }
}
