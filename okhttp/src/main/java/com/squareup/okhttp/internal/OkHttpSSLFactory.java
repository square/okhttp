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

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

public class OkHttpSslFactory extends SSLSocketFactory {

    private SSLSocketFactory decoratedFactory;

    static final String[] ENABLED_PROTOCOLS = { "TLSv1.2", "TLSv1.1", "TLSv1" };

    static final String[] ENABLED_CIPHERS  = {
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
            "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "SSL_RSA_WITH_3DES_EDE_CBC_SHA",
            "SSL_RSA_WITH_RC4_128_SHA",
            "SSL_RSA_WITH_RC4_128_MD5",
    };


    public OkHttpSslFactory(SSLSocketFactory decoratedFactory) {
        this.decoratedFactory = decoratedFactory;
    }

    @Override
    public Socket createSocket(String s, int i) throws IOException {
        return decorateSocket(decoratedFactory.createSocket(s, i));
    }

    @Override
    public Socket createSocket(String s, int i, InetAddress inetAddress, int i2)
            throws IOException {
        return decorateSocket(decoratedFactory.createSocket(s, i, inetAddress, i2));
    }

    @Override
    public Socket createSocket(InetAddress inetAddress, int i) throws IOException {
        return decorateSocket(decoratedFactory.createSocket(inetAddress, i));
    }

    @Override
    public Socket createSocket(Socket socket,  String host, int port, boolean autoClose)
            throws IOException {
        return decorateSocket(decoratedFactory.createSocket(socket, host, port, autoClose));
    }

    @Override
    public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress2, int i2)
            throws IOException {
        return decorateSocket(decoratedFactory.createSocket(inetAddress, i, inetAddress2, i2));
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return decoratedFactory.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return decoratedFactory.getSupportedCipherSuites();
    }

    private Socket decorateSocket(Socket socket) {
        if (socket instanceof SSLSocket) {
            applyProtocolOrder((SSLSocket) socket);
            applyCipherSuites((SSLSocket) socket);
        }

        return socket;
    }

    private void applyCipherSuites(SSLSocket sslSocket) {
        sslSocket.setEnabledCipherSuites(intersect(sslSocket.getSupportedCipherSuites(),
                ENABLED_CIPHERS));
    }

    private void applyProtocolOrder(SSLSocket sslSocket) {
        sslSocket.setEnabledProtocols(intersect(sslSocket.getSupportedProtocols(),
                ENABLED_PROTOCOLS));
    }

    private String[] intersect(String[] supported, String[] enabled) {
        List<String> supportedList = Arrays.asList(supported);

        supportedList.retainAll(Arrays.asList(enabled));

        return supportedList.toArray(new String[supportedList.size()]);
    }
}

