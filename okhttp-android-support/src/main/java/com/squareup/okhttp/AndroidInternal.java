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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.InternalCache;
import com.squareup.okhttp.internal.huc.CacheAdapter;

import java.net.ResponseCache;

/**
 * A class for back doors for Android's use of OkHttp within the Android platform.
 */
public class AndroidInternal {

  private AndroidInternal() {
  }

  /** Sets the response cache to be used to read and write cached responses. */
  public static void setResponseCache(OkUrlFactory okUrlFactory, ResponseCache responseCache) {
    okUrlFactory.client().setInternalCache(
        responseCache != null ? new CacheAdapter(responseCache) : null);
  }

  public static ResponseCache getResponseCache(OkUrlFactory okUrlFactory) {
    InternalCache cache = okUrlFactory.client().internalCache();
    return cache instanceof CacheAdapter ? ((CacheAdapter) cache).getDelegate() : null;
  }
}
