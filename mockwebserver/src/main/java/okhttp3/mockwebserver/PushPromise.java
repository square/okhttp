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
package okhttp3.mockwebserver;

import okhttp3.Headers;

/** An HTTP request initiated by the server. */
public final class PushPromise {
  private final String method;
  private final String path;
  private final Headers headers;
  private final MockResponse response;

  public PushPromise(String method, String path, Headers headers, MockResponse response) {
    this.method = method;
    this.path = path;
    this.headers = headers;
    this.response = response;
  }

  public String method() {
    return method;
  }

  public String path() {
    return path;
  }

  public Headers headers() {
    return headers;
  }

  public MockResponse response() {
    return response;
  }
}
