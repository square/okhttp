/*
 * Copyright (C) 2013 Square, Inc.
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

/**
 * A failure attempting to retrieve an HTTP response.
 *
 * <h3>Warning: Experimental OkHttp 2.0 API</h3>
 * This class is in beta. APIs are subject to change!
 */
/* OkHttp 2.0: public */ class Failure {
  private final Request request;
  private final Throwable exception;

  private Failure(Builder builder) {
    this.request = builder.request;
    this.exception = builder.exception;
  }

  public Request request() {
    return request;
  }

  public Throwable exception() {
    return exception;
  }

  public static class Builder {
    private Request request;
    private Throwable exception;

    public Builder request(Request request) {
      this.request = request;
      return this;
    }

    public Builder exception(Throwable exception) {
      this.exception = exception;
      return this;
    }

    public Failure build() {
      return new Failure(this);
    }
  }
}
