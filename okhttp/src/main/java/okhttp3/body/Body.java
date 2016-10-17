package okhttp3.body;

import okhttp3.MediaType;
import okio.BufferedSink;
import okio.BufferedSource;

import java.io.IOException;

public interface Body extends Readable, Writable {
  /**
   * Returns the {@link MediaType} for this body.
   */
  MediaType contentType();

  /**
   * Returns the content length or -1 when the content length is unknown.
   */
  long contentLength();

  /**
   * Returns a {@link BufferedSource} to read the contents of this {@link Body}.
   * throws {link {@link UnsupportedOperationException}} if this body is not readable.
   */
  BufferedSource source();

  /**
   * Implementations of this method should write the contents of this body to the
   * given {@link BufferedSink}.
   * throws {link {@link UnsupportedOperationException}} if this body is not writable.
   */
  void writeTo(BufferedSink sink) throws IOException;
}
