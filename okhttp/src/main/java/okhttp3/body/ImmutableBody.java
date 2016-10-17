package okhttp3.body;

import okhttp3.MediaType;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;

import java.io.IOException;

final class ImmutableBody implements Body {
  private final MediaType contentType;
  private final long contentLength;
  private final byte[] content;

  ImmutableBody(MediaType contentType, long contentLength, byte[] content) {
    this.contentType = contentType;
    this.contentLength = contentLength;
    this.content = content;
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
    return new Buffer().write(content);
  }

  @Override
  public void writeTo(BufferedSink sink) throws IOException {
    sink.write(content);
  }
}
