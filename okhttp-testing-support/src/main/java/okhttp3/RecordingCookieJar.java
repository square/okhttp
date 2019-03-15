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
package okhttp3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

public final class RecordingCookieJar implements CookieJar {
  private final Deque<List<Cookie>> requestCookies = new ArrayDeque<>();
  private final Deque<List<Cookie>> responseCookies = new ArrayDeque<>();

  public void enqueueRequestCookies(Cookie... cookies) {
    requestCookies.add(asList(cookies));
  }

  public List<Cookie> takeResponseCookies() {
    return responseCookies.removeFirst();
  }

  public void assertResponseCookies(String... cookies) {
    List<Cookie> actualCookies = takeResponseCookies();
    List<String> actualCookieStrings = new ArrayList<>();
    for (Cookie cookie : actualCookies) {
      actualCookieStrings.add(cookie.toString());
    }
    assertThat(actualCookieStrings).containsExactly(cookies);
  }

  @Override public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
    responseCookies.add(cookies);
  }

  @Override public List<Cookie> loadForRequest(HttpUrl url) {
    if (requestCookies.isEmpty()) return Collections.emptyList();
    return requestCookies.removeFirst();
  }
}
