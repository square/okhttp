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
package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSink;

import static okhttp3.HttpUrl.FORM_ENCODE_SET;
import static okhttp3.HttpUrl.percentDecode;

public final class FormBody extends RequestBody {
  private static final MediaType CONTENT_TYPE =
      MediaType.parse("application/x-www-form-urlencoded");

  private final List<String> encodedNames;
  private final List<String> encodedValues;

  private FormBody(List<String> encodedNames, List<String> encodedValues) {
    this.encodedNames = Util.immutableList(encodedNames);
    this.encodedValues = Util.immutableList(encodedValues);
  }

  /** The number of key-value pairs in this form-encoded body. */
  public int size() {
    return encodedNames.size();
  }

  public String encodedName(int index) {
    return encodedNames.get(index);
  }

  public String name(int index) {
    return percentDecode(encodedName(index), true);
  }

  public String encodedValue(int index) {
    return encodedValues.get(index);
  }

  public String value(int index) {
    return percentDecode(encodedValue(index), true);
  }

  @Override public MediaType contentType() {
    return CONTENT_TYPE;
  }

  @Override public long contentLength() {
    return writeOrCountBytes(null, true);
  }

  @Override public void writeTo(BufferedSink sink) throws IOException {
    writeOrCountBytes(sink, false);
  }

  /**
   * Either writes this request to {@code sink} or measures its content length. We have one method
   * do double-duty to make sure the counting and content are consistent, particularly when it comes
   * to awkward operations like measuring the encoded length of header strings, or the
   * length-in-digits of an encoded integer.
   */
  private long writeOrCountBytes(BufferedSink sink, boolean countBytes) {
    long byteCount = 0L;

    Buffer buffer;
    if (countBytes) {
      buffer = new Buffer();
    } else {
      buffer = sink.buffer();
    }

    for (int i = 0, size = encodedNames.size(); i < size; i++) {
      if (i > 0) buffer.writeByte('&');
      buffer.writeUtf8(encodedNames.get(i));
      buffer.writeByte('=');
      buffer.writeUtf8(encodedValues.get(i));
    }

    if (countBytes) {
      byteCount = buffer.size();
      buffer.clear();
    }

    return byteCount;
  }

  public static final class Builder {
    private final List<String> names = new ArrayList<>();
    private final List<String> values = new ArrayList<>();

    public Builder add(String name, String value) {
      names.add(HttpUrl.canonicalize(name, FORM_ENCODE_SET, false, false, true, true));
      values.add(HttpUrl.canonicalize(value, FORM_ENCODE_SET, false, false, true, true));
      return this;
    }

    public Builder addEncoded(String name, String value) {
      names.add(HttpUrl.canonicalize(name, FORM_ENCODE_SET, true, false, true, true));
      values.add(HttpUrl.canonicalize(value, FORM_ENCODE_SET, true, false, true, true));
      return this;
    }

    public FormBody build() {
      return new FormBody(names, values);
    }
  }
}
