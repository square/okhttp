/*
 * Copyright (C) 2012 Square, Inc.
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

import com.squareup.okhttp.internal.net.http.HttpURLConnectionImpl;
import com.squareup.okhttp.internal.net.http.HttpsURLConnectionImpl;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

/**
 * Configures and creates HTTP connections.
 */
public final class OkHttpClient {
    private Proxy proxy;

    public OkHttpClient setProxy(Proxy proxy) {
        this.proxy = proxy;
        return this;
    }

    public HttpURLConnection open(URL url) {
        String protocol = url.getProtocol();
        if (protocol.equals("http")) {
            return new HttpURLConnectionImpl(url, 80, proxy);
        } else if (protocol.equals("https")) {
            return new HttpsURLConnectionImpl(url, 443, proxy);
        } else {
            throw new IllegalArgumentException();
        }
    }
}
