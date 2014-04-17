package com.squareup.okhttp.apache;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Request;
import java.io.IOException;
import okio.BufferedSink;
import org.apache.http.Header;
import org.apache.http.HttpEntity;

/** Adapts an {@link HttpEntity} to OkHttp's {@link Request.Body}. */
final class HttpEntityBody extends Request.Body {
  private static final String DEFAULT_MEDIA_TYPE = "application/octet-stream";

  private final HttpEntity entity;
  private final MediaType mediaType;

  HttpEntityBody(HttpEntity entity) {
    this.entity = entity;

    // Apache is forgiving and lets you skip specifying a content type with an entity. OkHttp is
    // not forgiving so we fall back to a generic type if it's missing.
    Header contentType = entity.getContentType();
    mediaType = MediaType.parse(contentType != null ? contentType.getValue() : DEFAULT_MEDIA_TYPE);
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
