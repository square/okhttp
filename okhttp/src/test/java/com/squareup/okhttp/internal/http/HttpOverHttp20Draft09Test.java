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
package com.squareup.okhttp.internal.http;

public class HttpOverHttp20Draft09Test extends HttpOverSpdyTest {

  public HttpOverHttp20Draft09Test() {
    super("HTTP-draft-09/2.0");
    // TODO: is this really the whole authority, or just the host/port?
    // https://github.com/http2/http2-spec/issues/334
    this.hostHeader = ":authority";
  }
}
