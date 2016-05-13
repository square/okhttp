package okhttp3.apache;

import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import org.apache.http.HttpEntity;

/** Adapts an {@link HttpEntity} to OkHttp's {@link RequestBody}. */
final class HttpEntityBody extends RequestBody {
  private static final MediaType DEFAULT_MEDIA_TYPE = MediaType.parse("application/octet-stream");

  private final HttpEntity entity;
  private final MediaType mediaType;

  HttpEntityBody(HttpEntity entity, String contentTypeHeader) {
    this.entity = entity;

    if (contentTypeHeader != null) {
      mediaType = MediaType.parse(contentTypeHeader);
    } else if (entity.getContentType() != null) {
      mediaType = MediaType.parse(entity.getContentType().getValue());
    } else {
      // Apache is forgiving and lets you skip specifying a content type with an entity. OkHttp is
      // not forgiving so we fall back to a generic type if it's missing.
      mediaType = DEFAULT_MEDIA_TYPE;
    }
  }

  @Override public long contentLength() {
    return entity.getContentLength();
  }

  @Override public MediaType contentType() {
    return mediaType;
  }

  @Override public void writeTo(BufferedSink sink) throws IOException {
    entity.writeTo(sink.outputStream());
  }
}
