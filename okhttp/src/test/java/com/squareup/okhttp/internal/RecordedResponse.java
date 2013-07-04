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
package com.squareup.okhttp.internal;

import com.squareup.okhttp.Failure;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * A received response or failure recorded by the response recorder.
 */
public class RecordedResponse {
  public final Request request;
  public final Response response;
  public final String body;
  public final Failure failure;

  RecordedResponse(Request request, Response response, String body, Failure failure) {
    this.request = request;
    this.response = response;
    this.body = body;
    this.failure = failure;
  }

  public RecordedResponse assertCode(int expectedCode) {
    assertEquals(expectedCode, response.code());
    return this;
  }

  public RecordedResponse assertContainsHeaders(String... expectedHeaders) {
    List<String> actualHeaders = new ArrayList<String>();
    for (int i = 0; i < response.headerCount(); i++) {
      actualHeaders.add(response.headerName(i) + ": " + response.headerValue(i));
    }
    if (!actualHeaders.containsAll(Arrays.asList(expectedHeaders))) {
      fail("Expected: " + actualHeaders + "\nto contain: " + Arrays.toString(expectedHeaders));
    }
    return this;
  }

  public RecordedResponse assertBody(String expectedBody) {
    assertEquals(expectedBody, body);
    return this;
  }
}
