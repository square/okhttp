package okhttp3.body;

import okhttp3.MediaType;

public final class Bodies {
  /**
   * Creates an immutable body that can be used to write or read data.
   */
  public static Body create(MediaType contentType, long contentLength, byte[] content) {
    return new ImmutableBody(contentType, contentLength, content);
  }

  /**
   * Creates a writable body to stream data out.
   */
  public static Body create(MediaType contentType, long contentLength, Writable writable) {
    return new WritableBody(contentType, contentLength, writable);
  }

  /**
   * Creates a readable body to stream data in.
   */
  public static Body create(MediaType contentType, long contentLength, Readable readable) {
    return new ReadableBody(contentType, contentLength, readable);
  }
}
