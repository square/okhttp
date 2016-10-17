package okhttp3.body;

import okhttp3.MediaType;
import okio.BufferedSink;
import okio.BufferedSource;

import java.io.IOException;

class ReadableBody implements Body {
  private final MediaType contentType;
  private final long contentLength;
  private final Readable readable;

  ReadableBody(MediaType contentType, long contentLength, Readable readable) {
    if (readable == null) throw new NullPointerException("readable is null");

    this.contentType = contentType;
    this.contentLength = contentLength;
    this.readable = readable;
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
    return readable.source();
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    throw new UnsupportedOperationException();
  }
}
