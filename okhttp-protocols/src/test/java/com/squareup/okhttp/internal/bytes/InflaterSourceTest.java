package com.squareup.okhttp.internal.bytes;

import com.squareup.okhttp.internal.Base64;
import com.squareup.okhttp.internal.Util;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class InflaterSourceTest {
  @Test public void inflate() throws Exception {
    OkBuffer deflated = decodeBase64("eJxzz09RyEjNKVAoLdZRKE9VL0pVyMxTKMlIVchIzEspVshPU0jNS8/MS00tK"
        + "tYDAF6CD5s=");
    OkBuffer inflated = inflate(deflated);
    assertEquals("God help us, we're in the hands of engineers.", readUtf8(inflated));
  }

  @Test public void inflateTruncated() throws Exception {
    OkBuffer deflated = decodeBase64("eJxzz09RyEjNKVAoLdZRKE9VL0pVyMxTKMlIVchIzEspVshPU0jNS8/MS00tK"
        + "tYDAF6CDw==");
    try {
      inflate(deflated);
      fail();
    } catch (EOFException expected) {
    }
  }

  @Test public void inflateWellCompressed() throws Exception {
    OkBuffer deflated = decodeBase64("eJztwTEBAAAAwqCs61/CEL5AAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB8B"
        + "tFeWvE=\n");
    String original = repeat('a', 1024 * 1024);
    OkBuffer inflated = inflate(deflated);
    assertEquals(original, readUtf8(inflated));
  }

  @Test public void inflatePoorlyCompressed() throws Exception {
    ByteString original = randomBytes(1024 * 1024);
    OkBuffer deflated = deflate(toBuffer(original));
    OkBuffer inflated = inflate(deflated);
    assertEquals(original, inflated.readByteString((int) inflated.byteCount()));
  }

  private OkBuffer decodeBase64(String s) {
    OkBuffer result = new OkBuffer();
    byte[] data = Base64.decode(s.getBytes(Util.UTF_8));
    result.write(data, 0, data.length);
    return result;
  }

  private String readUtf8(OkBuffer buffer) {
    return buffer.readUtf8((int) buffer.byteCount());
  }

  /** Use DeflaterOutputStream to deflate source. */
  private OkBuffer deflate(OkBuffer buffer) throws IOException {
    OkBuffer result = new OkBuffer();
    Sink sink = OkBuffers.sink(new DeflaterOutputStream(OkBuffers.outputStream(result)));
    sink.write(buffer, buffer.byteCount(), Deadline.NONE);
    sink.close(Deadline.NONE);
    return result;
  }

  private OkBuffer toBuffer(ByteString byteString) {
    OkBuffer byteStringBuffer = new OkBuffer();
    byteStringBuffer.write(byteString);
    return byteStringBuffer;
  }

  /** Returns a new buffer containing the inflated contents of {@code deflated}. */
  private OkBuffer inflate(OkBuffer deflated) throws IOException {
    OkBuffer result = new OkBuffer();
    InflaterSource source = new InflaterSource(deflated, new Inflater());
    while (source.read(result, Integer.MAX_VALUE, Deadline.NONE) != -1) {
    }
    return result;
  }

  private ByteString randomBytes(int length) {
    Random random = new Random(0);
    byte[] randomBytes = new byte[length];
    random.nextBytes(randomBytes);
    return ByteString.of(randomBytes);
  }

  private String repeat(char c, int count) {
    char[] array = new char[count];
    Arrays.fill(array, c);
    return new String(array);
  }
}
