/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;

/**
 * Fluent API to build <a href="http://www.ietf.org/rfc/rfc2387.txt">RFC
 * 2387</a>-compliant request bodies.
 */
public final class MultipartBuilder {
  /**
   * The "mixed" subtype of "multipart" is intended for use when the body
   * parts are independent and need to be bundled in a particular order. Any
   * "multipart" subtypes that an implementation does not recognize must be
   * treated as being of subtype "mixed".
   */
  public static final MediaType MIXED = MediaType.parse("multipart/mixed");

  /**
   * The "multipart/alternative" type is syntactically identical to
   * "multipart/mixed", but the semantics are different. In particular, each
   * of the body parts is an "alternative" version of the same information.
   */
  public static final MediaType ALTERNATIVE = MediaType.parse("multipart/alternative");

  /**
   * This type is syntactically identical to "multipart/mixed", but the
   * semantics are different. In particular, in a digest, the default {@code
   * Content-Type} value for a body part is changed from "text/plain" to
   * "message/rfc822".
   */
  public static final MediaType DIGEST = MediaType.parse("multipart/digest");

  /**
   * This type is syntactically identical to "multipart/mixed", but the
   * semantics are different. In particular, in a parallel entity, the order
   * of body parts is not significant.
   */
  public static final MediaType PARALLEL = MediaType.parse("multipart/parallel");

  /**
   * The media-type multipart/form-data follows the rules of all multipart
   * MIME data streams as outlined in RFC 2046. In forms, there are a series
   * of fields to be supplied by the user who fills out the form. Each field
   * has a name. Within a given form, the names are unique.
   */
  public static final MediaType FORM = MediaType.parse("multipart/form-data");

  private static final byte[] COLONSPACE = { ':', ' ' };
  private static final byte[] CRLF = { '\r', '\n' };
  private static final byte[] DASHDASH = { '-', '-' };

  private final ByteString boundary;
  private MediaType type = MIXED;
  private long length = 0;

  // Parallel lists of nullable headings (boundary + headers) and non-null bodies.
  private final List<Buffer> partHeadings = new ArrayList<>();
  private final List<RequestBody> partBodies = new ArrayList<>();

  /** Creates a new multipart builder that uses a random boundary token. */
  public MultipartBuilder() {
    this(UUID.randomUUID().toString());
  }

  /**
   * Creates a new multipart builder that uses {@code boundary} to separate
   * parts. Prefer the no-argument constructor to defend against injection
   * attacks.
   */
  public MultipartBuilder(String boundary) {
    this.boundary = ByteString.encodeUtf8(boundary);
  }

  /**
   * Set the MIME type. Expected values for {@code type} are {@link #MIXED} (the
   * default), {@link #ALTERNATIVE}, {@link #DIGEST}, {@link #PARALLEL} and
   * {@link #FORM}.
   */
  public MultipartBuilder type(MediaType type) {
    if (type == null) {
      throw new NullPointerException("type == null");
    }
    if (!type.type().equals("multipart")) {
      throw new IllegalArgumentException("multipart != " + type);
    }
    this.type = type;
    return this;
  }

  /** Add a part to the body. */
  public MultipartBuilder addPart(RequestBody body) throws IOException {
    return addPart(null, body);
  }

  /** Add a part to the body. */
  public MultipartBuilder addPart(Headers headers, RequestBody body) throws IOException {
    if (body == null) {
      throw new NullPointerException("body == null");
    }
    if (headers != null && headers.get("Content-Type") != null) {
      throw new IllegalArgumentException("Unexpected header: Content-Type");
    }
    if (headers != null && headers.get("Content-Length") != null) {
      throw new IllegalArgumentException("Unexpected header: Content-Length");
    }

    Buffer heading = createPartHeading(headers, body, partHeadings.isEmpty());
    partHeadings.add(heading);
    partBodies.add(body);

    long bodyContentLength = body.contentLength();
    if (bodyContentLength == -1) {
      length = -1;
    } else if (length != -1) {
      length += heading.size() + bodyContentLength;
    }

    return this;
  }

  /**
   * Appends a quoted-string to a StringBuilder.
   *
   * <p>RFC 2388 is rather vague about how one should escape special characters
   * in form-data parameters, and as it turns out Firefox and Chrome actually
   * do rather different things, and both say in their comments that they're
   * not really sure what the right approach is. We go with Chrome's behavior
   * (which also experimentally seems to match what IE does), but if you
   * actually want to have a good chance of things working, please avoid
   * double-quotes, newlines, percent signs, and the like in your field names.
   */
  private static StringBuilder appendQuotedString(StringBuilder target, String key) {
    target.append('"');
    for (int i = 0, len = key.length(); i < len; i++) {
      char ch = key.charAt(i);
      switch (ch) {
        case '\n':
          target.append("%0A");
          break;
        case '\r':
          target.append("%0D");
          break;
        case '"':
          target.append("%22");
          break;
        default:
          target.append(ch);
          break;
      }
    }
    target.append('"');
    return target;
  }

  /** Add a form data part to the body. */
  public MultipartBuilder addFormDataPart(String name, String value) throws IOException {
    return addFormDataPart(name, null, RequestBody.create(null, value));
  }

  /** Add a form data part to the body. */
  public MultipartBuilder addFormDataPart(String name, String filename, RequestBody value)
      throws IOException {
    if (name == null) {
      throw new NullPointerException("name == null");
    }
    StringBuilder disposition = new StringBuilder("form-data; name=");
    appendQuotedString(disposition, name);

    if (filename != null) {
      disposition.append("; filename=");
      appendQuotedString(disposition, filename);
    }

    return addPart(Headers.of("Content-Disposition", disposition.toString()), value);
  }

  /** Creates a part "heading" from the boundary and any real or generated headers. */
  private Buffer createPartHeading(Headers headers, RequestBody body, boolean isFirst)
      throws IOException {
    Buffer sink = new Buffer();

    if (!isFirst) {
      sink.write(CRLF);
    }
    sink.write(DASHDASH);
    sink.write(boundary);
    sink.write(CRLF);

    if (headers != null) {
      for (int i = 0; i < headers.size(); i++) {
        sink.writeUtf8(headers.name(i))
            .write(COLONSPACE)
            .writeUtf8(headers.value(i))
            .write(CRLF);
      }
    }

    MediaType contentType = body.contentType();
    if (contentType != null) {
      sink.writeUtf8("Content-Type: ")
          .writeUtf8(contentType.toString())
          .write(CRLF);
    }

    long contentLength = body.contentLength();
    if (contentLength != -1) {
      sink.writeUtf8("Content-Length: ")
          .writeUtf8(Long.toString(contentLength))
          .write(CRLF);
    }

    sink.write(CRLF);

    return sink;
  }

  /** Assemble the specified parts into a request body. */
  public RequestBody build() {
    if (partHeadings.isEmpty()) {
      throw new IllegalStateException("Multipart body must have at least one part.");
    }
    return new MultipartRequestBody(type, boundary, partHeadings, partBodies, length);
  }

  private static final class MultipartRequestBody extends RequestBody {
    private final ByteString boundary;
    private final MediaType contentType;
    private final List<Buffer> partHeadings;
    private final List<RequestBody> partBodies;
    private final long length;

    public MultipartRequestBody(MediaType type, ByteString boundary, List<Buffer> partHeadings,
        List<RequestBody> partBodies, long length) {
      if (type == null) throw new NullPointerException("type == null");

      this.boundary = boundary;
      this.contentType = MediaType.parse(type + "; boundary=" + boundary.utf8());
      this.partHeadings = Util.immutableList(partHeadings);
      this.partBodies = Util.immutableList(partBodies);
      if (length != -1) {
        // Add the length of the final boundary.
        length += CRLF.length + DASHDASH.length + boundary.size() + DASHDASH.length + CRLF.length;
      }
      this.length = length;
    }

    @Override public long contentLength() {
      return length;
    }

    @Override public MediaType contentType() {
      return contentType;
    }

    @Override public void writeTo(BufferedSink sink) throws IOException {
      for (int i = 0, size = partHeadings.size(); i < size; i++) {
        sink.writeAll(partHeadings.get(i).clone());
        partBodies.get(i).writeTo(sink);
      }

      sink.write(CRLF);
      sink.write(DASHDASH);
      sink.write(boundary);
      sink.write(DASHDASH);
      sink.write(CRLF);
    }
  }
}
