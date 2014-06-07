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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Fluent API to build <a href="http://www.w3.org/MarkUp/html-spec/html-spec_8.html#SEC8.2.1">HTML
 * 2.0</a>-compliant form data.
 */
public final class FormEncodingBuilder {
  private static final MediaType CONTENT_TYPE
      = MediaType.parse("application/x-www-form-urlencoded");

  private final StringBuilder content = new StringBuilder();

  /** Add new key-value pair. */
  public FormEncodingBuilder add(String name, String value) {
    if (content.length() > 0) {
      content.append('&');
    }
    try {
      content.append(URLEncoder.encode(name, "UTF-8"))
          .append('=')
          .append(URLEncoder.encode(value, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
    return this;
  }

  public RequestBody build() {
    if (content.length() == 0) {
      throw new IllegalStateException("Form encoded body must have at least one part.");
    }

    // Convert to bytes so RequestBody.create() doesn't add a charset to the content-type.
    byte[] contentBytes = content.toString().getBytes(Util.UTF_8);
    return RequestBody.create(CONTENT_TYPE, contentBytes);
  }
}
