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
package com.squareup.okhttp.internal.spdy;

import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.RecordedRequest;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.mockspdyserver.MockSpdyServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Collection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Test how SPDY interacts with HTTP features.
 */
public final class HttpOverSpdyTest {
    private static final HostnameVerifier NULL_HOSTNAME_VERIFIER = new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private static final SSLContext sslContext;
    static {
        try {
            sslContext = new SslContextBuilder(InetAddress.getLocalHost().getHostName()).build();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }
    private final MockSpdyServer server = new MockSpdyServer(sslContext.getSocketFactory());
    private final String hostName = server.getHostName();
    private final OkHttpClient client = new OkHttpClient();

    @Before public void setUp() throws Exception {
        client.setSSLSocketFactory(sslContext.getSocketFactory());
        client.setHostnameVerifier(NULL_HOSTNAME_VERIFIER);
    }

    @After public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test public void get() throws Exception {
        MockResponse response = new MockResponse().setBody("ABCDE");
        server.enqueue(response);
        server.play();

        HttpURLConnection connection = client.open(server.getUrl("/foo"));
        assertContent("ABCDE", connection, Integer.MAX_VALUE);

        RecordedRequest request = server.takeRequest();
        assertEquals("GET /foo HTTP/1.1", request.getRequestLine());
        assertContains(request.getHeaders(), ":scheme: https");
        assertContains(request.getHeaders(), ":host: " + hostName + ":" + server.getPort());
    }

    private <T> void assertContains(Collection<T> collection, T value) {
        assertTrue(collection.toString(), collection.contains(value));
    }

    private void assertContent(String expected, URLConnection connection, int limit)
            throws IOException {
        connection.connect();
        assertEquals(expected, readAscii(connection.getInputStream(), limit));
        ((HttpURLConnection) connection).disconnect();
    }

    private String readAscii(InputStream in, int count) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int value = in.read();
            if (value == -1) {
                in.close();
                break;
            }
            result.append((char) value);
        }
        return result.toString();
    }
}
