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
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.net.URL;

/**
 * Configures and creates HTTP connections.
 */
public final class OkHttpClient {
    private Proxy proxy;
    private ProxySelector proxySelector;
    private CookieHandler cookieHandler;
    private ResponseCache responseCache;

    /**
     * Sets the HTTP proxy that will be used by connections created by this
     * client. This takes precedence over {@link #setProxySelector}, which is
     * only honored when this proxy is null (which it is by default). To disable
     * proxy use completely, call {@code setProxy(Proxy.NO_PROXY)}.
     */
    public OkHttpClient setProxy(Proxy proxy) {
        this.proxy = proxy;
        return this;
    }

    /**
     * Sets the proxy selection policy to be used if no {@link #setProxy proxy}
     * is specified explicitly. The proxy selector may return multiple proxies;
     * in that case they will be tried in sequence until a successful connection
     * is established.
     *
     * <p>If unset, the {@link ProxySelector#getDefault() system-wide default}
     * proxy selector will be used.
     */
    public OkHttpClient setProxySelector(ProxySelector proxySelector) {
        this.proxySelector = proxySelector;
        return this;
    }

    /**
     * Sets the cookie handler to be used to read outgoing cookies and write
     * incoming cookies.
     *
     * <p>If unset, the {@link CookieHandler#getDefault() system-wide default}
     * cookie handler will be used.
     */
    public OkHttpClient setCookieHandler(CookieHandler cookieHandler) {
        this.cookieHandler = cookieHandler;
        return this;
    }

    /**
     * Sets the response cache to be used to read and write cached responses.
     *
     * <p>If unset, the {@link ResponseCache#getDefault() system-wide default}
     * response cache will be used.
     */
    public OkHttpClient setResponseCache(ResponseCache responseCache) {
        this.responseCache = responseCache;
        return this;
    }

    public HttpURLConnection open(URL url) {
        ProxySelector proxySelector = this.proxySelector != null
                ? this.proxySelector
                : ProxySelector.getDefault();
        CookieHandler cookieHandler = this.cookieHandler != null
                ? this.cookieHandler
                : CookieHandler.getDefault();
        ResponseCache responseCache = this.responseCache != null
                ? this.responseCache
                : ResponseCache.getDefault();

        String protocol = url.getProtocol();
        if (protocol.equals("http")) {
            return new HttpURLConnectionImpl(
                    url, 80, proxy, proxySelector, cookieHandler, responseCache);
        } else if (protocol.equals("https")) {
            return new HttpsURLConnectionImpl(
                    url, 443, proxy, proxySelector, cookieHandler, responseCache);
        } else {
            throw new IllegalArgumentException();
        }
    }
}
