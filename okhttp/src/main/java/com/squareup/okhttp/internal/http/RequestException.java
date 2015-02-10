/*
 * Copyright (C) 2015 Square, Inc.
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

import java.io.IOException;

/**
 * Indicates a problem with interpreting a request. It may indicate there was a problem with the
 * request itself, or the environment being used to interpret the request (network failure, etc.).
 */
public final class RequestException extends Exception {

  public RequestException(IOException cause) {
    super(cause);
  }

  @Override
  public IOException getCause() {
    return (IOException) super.getCause();
  }
}
