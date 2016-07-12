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
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

public class AbstractResponseCache extends ResponseCache {
  @Override public CacheResponse get(URI uri, String requestMethod,
      Map<String, List<String>> requestHeaders) throws IOException {
    return null;
  }

  @Override public CacheRequest put(URI uri, URLConnection connection) throws IOException {
    return null;
  }

  public static URI toUri(URL serverUrl) {
    try {
      return serverUrl.toURI();
    } catch (URISyntaxException e) {
      throw new AssertionError(e);
    }
  }
}
