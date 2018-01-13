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

public final class HttpMethodBehavior {
  private final boolean invalidatesCache;
  private final boolean requiresRequestBody;
  private final boolean permitsRequestBody;
  private final boolean redirectsWithBody;
  private final boolean redirectsToGet;

  HttpMethodBehavior(Builder builder) {
    if (builder.requiresRequestBody && !builder.permitsRequestBody) {
      throw new IllegalArgumentException("requiresRequestBody conflicts with permitsRequestBody");
    }
    if (builder.redirectsWithBody && !builder.permitsRequestBody) {
      throw new IllegalArgumentException("redirectsWithBody conflicts with permitsRequestBody");
    }
    if (builder.redirectsWithBody && builder.redirectsToGet) {
      throw new IllegalArgumentException("redirectsWithBody conflicts with redirectsToGet");
    }

    invalidatesCache = builder.invalidatesCache;
    requiresRequestBody = builder.requiresRequestBody;
    permitsRequestBody = builder.permitsRequestBody;
    redirectsWithBody = builder.redirectsWithBody;
    redirectsToGet = builder.redirectsToGet;
  }

  public boolean invalidatesCache() {
    return invalidatesCache;
  }

  public boolean requiresRequestBody() {
    return requiresRequestBody;
  }

  public boolean permitsRequestBody() {
    return permitsRequestBody;
  }

  public boolean redirectsWithBody() {
    return redirectsWithBody;
  }

  public boolean redirectsToGet() {
    return redirectsToGet;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  @Override public String toString() {
    return "HttpMethodBehavior{"
        + "invalidatesCache=" + invalidatesCache
        + ", requiresRequestBody=" + requiresRequestBody
        + ", permitsRequestBody=" + permitsRequestBody
        + ", redirectsWithBody=" + redirectsWithBody
        + ", redirectsToGet=" + redirectsToGet
        + '}';
  }

  public static class Builder {
    boolean invalidatesCache;
    boolean requiresRequestBody;
    boolean permitsRequestBody;
    boolean redirectsWithBody;
    boolean redirectsToGet;

    public Builder() {
      invalidatesCache = false;
      requiresRequestBody = false;
      permitsRequestBody = false;
      redirectsWithBody = false;
      redirectsToGet = true;
    }

    Builder(HttpMethodBehavior behavior) {
      invalidatesCache = behavior.invalidatesCache;
      requiresRequestBody = behavior.requiresRequestBody;
      permitsRequestBody = behavior.permitsRequestBody;
      redirectsWithBody = behavior.redirectsWithBody;
      redirectsToGet = behavior.redirectsToGet;
    }

    public Builder invalidatesCache(boolean invalidatesCache) {
      this.invalidatesCache = invalidatesCache;
      return this;
    }

    public Builder requiresRequestBody(boolean requiresRequestBody) {
      this.requiresRequestBody = requiresRequestBody;
      return this;
    }

    public Builder permitsRequestBody(boolean permitsRequestBody) {
      this.permitsRequestBody = permitsRequestBody;
      return this;
    }

    public Builder redirectsWithBody(boolean redirectsWithBody) {
      this.redirectsWithBody = redirectsWithBody;
      return this;
    }

    public Builder redirectsToGet(boolean redirectsToGet) {
      this.redirectsToGet = redirectsToGet;
      return this;
    }

    public HttpMethodBehavior build() {
      return new HttpMethodBehavior(this);
    }
  }
}
