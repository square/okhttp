// Copyright 2013 Square, Inc.
package com.squareup.okhttp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.squareup.okhttp.Utils.copyStream;
import static com.squareup.okhttp.Utils.isNotEmpty;
import static com.squareup.okhttp.Utils.isNotNull;
import static com.squareup.okhttp.Utils.isNotZero;
import static com.squareup.okhttp.Utils.isNull;

/** HTTP request data with associated headers. */
public interface Part {
  /** HTTP headers. */
  Map<String, String> getHeaders();

  /**
   * Write request data to the specified stream. For best performance use a
   * {@link java.io.BufferedOutputStream BufferedOutputStream}.
   */
  void writeBodyTo(OutputStream stream) throws IOException;

  /** Fluent API to build {@link Part} instances. */
  public class Builder {
    private static final int BUFFER_SIZE = 4096;

    private String headerType;
    int headerLength;
    private String headerLanguage;
    private String headerEncoding;
    private String headerDisposition;
    private File bodyFile;
    private InputStream bodyStream;
    private byte[] bodyBytes;
    private Multipart bodyMultipart;
    private boolean hasBody = false;

    private void checkSetBody() {
      if (hasBody) {
        throw new IllegalStateException("Only one body per part.");
      }
      hasBody = true;
    }

    /** Set the {@code Content-Type} header value. */
    public Builder contentType(String type) {
      isNotEmpty(type, "Type must not be empty.");
      isNull(headerType, "Type header already set.");
      isNull(bodyMultipart, "Type cannot be set with multipart body.");
      headerType = type;
      return this;
    }

    /** Set the {@code Content-Length} header value. */
    public Builder contentLength(int length) {
      if (length <= 0) {
        throw new IllegalStateException("Length must be greater than zero.");
      }
      isNotZero(headerLength, "Length header already set.");
      headerLength = length;
      return this;
    }

    /** Set the {@code Content-Language} header value. */
    public Builder contentLanguage(String language) {
      isNotEmpty(language, "Language must not be empty.");
      isNull(headerLanguage, "Language header already set.");
      headerLanguage = language;
      return this;
    }

    /** Set the {@code Content-Transfer-Encoding} header value. */
    public Builder contentEncoding(String encoding) {
      isNotEmpty(encoding, "Encoding must not be empty.");
      isNull(headerEncoding, "Encoding header already set.");
      headerEncoding = encoding;
      return this;
    }

    /** Set the {@code Content-Disposition} header value. */
    public Builder contentDisposition(String disposition) {
      isNotEmpty(disposition, "Disposition must not be empty.");
      isNull(headerDisposition, "Disposition header already set.");
      headerDisposition = disposition;
      return this;
    }

    /** Use the specified file as the body. */
    public Builder body(File body) {
      isNotNull(body, "File body must not be null.");
      checkSetBody();
      bodyFile = body;
      return this;
    }

    /** Use the specified stream as the body. */
    public Builder body(InputStream body) {
      isNotNull(body, "Stream body must not be null.");
      checkSetBody();
      bodyStream = body;
      return this;
    }

    /** Use the specified string as the body. */
    public Builder body(String body) {
      isNotNull(body, "String body must not be null.");
      checkSetBody();
      byte[] bytes;
      try {
        bytes = body.getBytes("UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new IllegalArgumentException("Unable to convert input to UTF-8: " + body, e);
      }
      bodyBytes = bytes;
      headerLength = bytes.length;
      return this;
    }

    /** Use the specified bytes as the body. */
    public Builder body(byte[] body) {
      isNotNull(body, "Byte array body must not be null.");
      checkSetBody();
      bodyBytes = body;
      headerLength = body.length;
      return this;
    }

    /** Use the specified {@link Multipart} as the body. */
    public Builder body(Multipart body) {
      isNotNull(body, "Multipart body must not be null.");
      if (headerType != null) {
        throw new IllegalStateException(
            "Content type must not be explicitly set for multipart body.");
      }
      checkSetBody();
      headerType = null;
      bodyMultipart = body;
      return this;
    }

    /** Assemble the specified headers and body into a {@link Part}. */
    public Part build() {
      Map<String, String> headers = new LinkedHashMap<String, String>();
      if (headerDisposition != null) {
        headers.put("Content-Disposition", headerDisposition);
      }
      if (headerType != null) {
        headers.put("Content-Type", headerType);
      }
      if (headerLength != 0) {
        headers.put("Content-Length", Integer.toString(headerLength));
      }
      if (headerLanguage != null) {
        headers.put("Content-Language", headerLanguage);
      }
      if (headerEncoding != null) {
        headers.put("Content-Transfer-Encoding", headerEncoding);
      }

      if (bodyBytes != null) {
        return new BytesPart(headers, bodyBytes);
      }
      if (bodyStream != null) {
        return new StreamPart(headers, bodyStream);
      }
      if (bodyFile != null) {
        return new FilePart(headers, bodyFile);
      }
      if (bodyMultipart != null) {
        headers.putAll(bodyMultipart.getHeaders());
        return new PartPart(headers, bodyMultipart);
      }
      throw new IllegalStateException("Part required body to be set.");
    }

    private abstract static class PartImpl implements Part {
      private final Map<String, String> headers;

      protected PartImpl(Map<String, String> headers) {
        this.headers = headers;
      }

      @Override public Map<String, String> getHeaders() {
        return headers;
      }
    }

    private static final class PartPart extends PartImpl {
      private final Part body;

      protected PartPart(Map<String, String> headers, Part body) {
        super(headers);
        this.body = body;
      }

      @Override public void writeBodyTo(OutputStream stream) throws IOException {
        body.writeBodyTo(stream);
      }
    }

    static final class BytesPart extends PartImpl {
      private final byte[] contents;

      BytesPart(Map<String, String> headers, byte[] contents) {
        super(headers);
        this.contents = contents;
      }

      @Override public void writeBodyTo(OutputStream out) throws IOException {
        out.write(contents);
      }
    }

    private static final class StreamPart extends PartImpl {
      private final InputStream in;
      private final byte[] buffer = new byte[BUFFER_SIZE];

      private StreamPart(Map<String, String> headers, InputStream in) {
        super(headers);
        this.in = in;
      }

      @Override public void writeBodyTo(OutputStream out) throws IOException {
        copyStream(in, out, buffer);
      }
    }

    private static final class FilePart extends PartImpl {
      private final File file;
      private final byte[] buffer = new byte[BUFFER_SIZE];

      private FilePart(Map<String, String> headers, File file) {
        super(headers);
        this.file = file;
      }

      @Override public void writeBodyTo(OutputStream out) throws IOException {
        InputStream in = null;
        try {
          in = new FileInputStream(file);
          copyStream(in, out, buffer);
        } finally {
          if (in != null) {
            try {
              in.close();
            } catch (IOException ignored) {
            }
          }
        }
      }
    }
  }
}
