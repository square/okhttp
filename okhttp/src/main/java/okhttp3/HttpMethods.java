/*
 * Copyright (C) 2018 Square, Inc.
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpMethods {
  private static final HttpMethodBehavior DEFAULT =
      new HttpMethodBehavior.Builder()
          .invalidatesCache(false)
          .requiresRequestBody(false)
          .permitsRequestBody(false)
          .redirectsWithBody(false)
          .redirectsToGet(true)
          .build();
  private static final HttpMethodBehavior REQUIRED_BODY_INVALIDATE =
      new HttpMethodBehavior.Builder()
          .invalidatesCache(true)
          .requiresRequestBody(true)
          .permitsRequestBody(true)
          .redirectsWithBody(false)
          .redirectsToGet(true)
          .build();
  private static final HttpMethodBehavior NO_BODY_INVALIDATE =
      new HttpMethodBehavior.Builder()
          .invalidatesCache(true)
          .requiresRequestBody(false)
          .permitsRequestBody(false)
          .redirectsWithBody(false)
          .redirectsToGet(true)
          .build();
  private static final HttpMethodBehavior REQUIRED_BODY =
      new HttpMethodBehavior.Builder()
          .invalidatesCache(false)
          .requiresRequestBody(true)
          .permitsRequestBody(true)
          .redirectsWithBody(false)
          .redirectsToGet(true)
          .build();
  private static final HttpMethodBehavior OPTIONAL_BODY_INVALIDATE =
      new HttpMethodBehavior.Builder()
          .invalidatesCache(true)
          .requiresRequestBody(false)
          .permitsRequestBody(true)
          .redirectsWithBody(false)
          .redirectsToGet(true)
          .build();
  private static final HttpMethodBehavior OPTIONAL_BODY =
      new HttpMethodBehavior.Builder()
          .invalidatesCache(false)
          .requiresRequestBody(false)
          .permitsRequestBody(true)
          .redirectsWithBody(false)
          .redirectsToGet(true)
          .build();
  private static final HttpMethodBehavior PROPFIND =
      new HttpMethodBehavior.Builder()
          .invalidatesCache(false)
          .requiresRequestBody(false)
          .permitsRequestBody(true)
          .redirectsWithBody(true)
          .redirectsToGet(false)
          .build();

  private static final HttpMethods DEFAULT_METHODS = new Builder()
      .addMethod("POST", REQUIRED_BODY_INVALIDATE)
      .addMethod("PATCH", REQUIRED_BODY_INVALIDATE)
      .addMethod("PUT", REQUIRED_BODY_INVALIDATE)
      .addMethod("DELETE", OPTIONAL_BODY_INVALIDATE)
      .addMethod("MOVE", NO_BODY_INVALIDATE)
      .addMethod("PROPPATCH", REQUIRED_BODY)
      .addMethod("REPORT", REQUIRED_BODY)
      .addMethod("OPTIONS", OPTIONAL_BODY)
      .addMethod("MKCOL", OPTIONAL_BODY)
      .addMethod("LOCK", OPTIONAL_BODY)
      .addMethod("PROPFIND", PROPFIND)
      .build();

  public static HttpMethods defaultMethods() {
    return DEFAULT_METHODS;
  }

  private final Map<String, HttpMethodBehavior> methods;

  private HttpMethods(Builder builder) {
    this.methods = Collections.unmodifiableMap(builder.methods);
  }

  public boolean invalidatesCache(String method) {
    return httpMethodBehavior(method).invalidatesCache();
  }

  public boolean requiresRequestBody(String method) {
    return httpMethodBehavior(method).requiresRequestBody();
  }

  public boolean permitsRequestBody(String method) {
    return httpMethodBehavior(method).permitsRequestBody();
  }

  public boolean redirectsWithBody(String method) {
    return httpMethodBehavior(method).redirectsWithBody();
  }

  public boolean redirectsToGet(String method) {
    return httpMethodBehavior(method).redirectsToGet();
  }

  private HttpMethodBehavior httpMethodBehavior(String method) {
    if (method == null) throw new NullPointerException("method == null");
    if (method.length() == 0) throw new IllegalArgumentException("method.length() == 0");
    HttpMethodBehavior behavior = methods.get(method);
    return behavior != null ? behavior : DEFAULT;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  @Override public String toString() {
    return "HttpMethods{"
        + "methods=" + methods
        + '}';
  }

  public static class Builder {
    final Map<String, HttpMethodBehavior> methods;

    Builder() {
      this.methods = new LinkedHashMap<>();
    }

    Builder(HttpMethods httpMethods) {
      this.methods = new LinkedHashMap<>(httpMethods.methods);
    }

    public Builder addMethod(String method, HttpMethodBehavior behavior) {
      if (method == null) throw new NullPointerException("methodName == null");
      if (method.length() == 0) throw new IllegalArgumentException("methodName.length() == 0");
      if (behavior == null) throw new NullPointerException("behavior == null");
      methods.put(method, behavior);
      return this;
    }

    public HttpMethods build() {
      return new HttpMethods(this);
    }
  }
}
