package okhttp3.body;

import okhttp3.MediaType;
import okio.BufferedSink;
import okio.BufferedSource;

import java.io.IOException;

public abstract class ReadableStreamingBody implements Body {
  private final Readable readable;

  ReadableStreamingBody(Readable readable) {
    if (readable == null) throw new NullPointerException("readable is null");

    this.readable = readable;
  }

  public abstract MediaType contentType();

  public abstract long contentLength();

  @Override
  public BufferedSource source() {
    return readable.source();
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    throw new UnsupportedOperationException();
  }
}
