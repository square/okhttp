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

import java.net.ResponseCache;
import okhttp3.internal.huc.CacheAdapter;

/**
 * Back doors to enable the use of OkHttp within the Android platform libraries. OkHttp is used to
 * provide the default {@link java.net.HttpURLConnection} / {@link javax.net.ssl.HttpsURLConnection}
 * implementation including support for a custom {@link ResponseCache}.
 */
public class AndroidInternal {

  private AndroidInternal() {
  }

  /** Sets the response cache to be used to read and write cached responses. */
  public static void setResponseCache(OkUrlFactory okUrlFactory, ResponseCache responseCache) {
    OkHttpClient client = okUrlFactory.client();
    if (responseCache instanceof OkCacheContainer) {
      // Avoid adding layers of wrappers. Rather than wrap the ResponseCache in yet another layer to
      // make the ResponseCache look like an InternalCache, we can unwrap the Cache instead.
      // This means that Cache stats will be correctly updated.
      OkCacheContainer okCacheContainer = (OkCacheContainer) responseCache;
      client.setCache(okCacheContainer.getCache());
    } else {
      client.setInternalCache(responseCache != null ? new CacheAdapter(responseCache) : null);
    }
  }
}
