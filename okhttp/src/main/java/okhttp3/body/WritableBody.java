package okhttp3.body;

import okhttp3.MediaType;
import okio.BufferedSink;
import okio.BufferedSource;

import java.io.IOException;

class WritableBody implements Body {
  private final MediaType contentType;
  private final long contentLength;
  private final Writable writable;

  WritableBody(MediaType contentType, long contentLength, Writable writable) {
    if (writable == null) throw new NullPointerException("writable is null");

    this.contentType = contentType;
    this.contentLength = contentLength;
    this.writable = writable;
  }

  @Override
  public MediaType contentType() {
    return contentType;
  }

  @Override
  public long contentLength() {
    return contentLength;
  }

  @Override
  public BufferedSource source() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    writable.writeTo(sink);
  }
}
