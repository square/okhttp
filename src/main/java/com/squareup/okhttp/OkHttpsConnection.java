/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.squareup.okhttp;

import java.net.URL;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

/**
 * An {@link java.net.HttpURLConnection} for HTTPS (<a
 * href="http://tools.ietf.org/html/rfc2818">RFC 2818</a>). A
 * connected {@code HttpsURLConnection} allows access to the
 * negotiated cipher suite, the server certificate chain, and the
 * client certificate chain if any.
 *
 * <h3>Providing an application specific X509TrustManager</h3>
 *
 * If an application wants to trust Certificate Authority (CA)
 * certificates that are not part of the system, it should specify its
 * own {@code X509TrustManager} via a {@code SSLSocketFactory} set on
 * the {@code HttpsURLConnection}. The {@code X509TrustManager} can be
 * created based on a {@code KeyStore} using a {@code
 * TrustManagerFactory} to supply trusted CA certificates. Note that
 * self-signed certificates are effectively their own CA and can be
 * trusted by including them in a {@code KeyStore}.
 *
 * <p>For example, to trust a set of certificates specified by a {@code KeyStore}:
 * <pre>   {@code
 *   KeyStore keyStore = ...;
 *   TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
 *   tmf.init(keyStore);
 *
 *   SSLContext context = SSLContext.getInstance("TLS");
 *   context.init(null, tmf.getTrustManagers(), null);
 *
 *   URL url = new URL("https://www.example.com/");
 *   HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
 *   urlConnection.setSSLSocketFactory(context.getSocketFactory());
 *   InputStream in = urlConnection.getInputStream();
 * }</pre>
 *
 * <p>It is possible to implement {@code X509TrustManager} directly
 * instead of using one created by a {@code
 * TrustManagerFactory}. While this is straightforward in the insecure
 * case of allowing all certificate chains to pass verification,
 * writing a proper implementation will usually want to take advantage
 * of {@link java.security.cert.CertPathValidator
 * CertPathValidator}. In general, it might be better to write a
 * custom {@code KeyStore} implementation to pass to the {@code
 * TrustManagerFactory} than to try and write a custom {@code
 * X509TrustManager}.
 *
 * <h3>Providing an application specific X509KeyManager</h3>
 *
 * A custom {@code X509KeyManager} can be used to supply a client
 * certificate and its associated private key to authenticate a
 * connection to the server. The {@code X509KeyManager} can be created
 * based on a {@code KeyStore} using a {@code KeyManagerFactory}.
 *
 * <p>For example, to supply client certificates from a {@code KeyStore}:
 * <pre>   {@code
 *   KeyStore keyStore = ...;
 *   KeyManagerFactory kmf = KeyManagerFactory.getInstance("X509");
 *   kmf.init(keyStore);
 *
 *   SSLContext context = SSLContext.getInstance("TLS");
 *   context.init(kmf.getKeyManagers(), null, null);
 *
 *   URL url = new URL("https://www.example.com/");
 *   HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
 *   urlConnection.setSSLSocketFactory(context.getSocketFactory());
 *   InputStream in = urlConnection.getInputStream();
 * }</pre>
 *
 * <p>A {@code X509KeyManager} can also be implemented directly. This
 * can allow an application to return a certificate and private key
 * from a non-{@code KeyStore} source or to specify its own logic for
 * selecting a specific credential to use when many may be present in
 * a single {@code KeyStore}.
 *
 * <h3>TLS Intolerance Support</h3>
 *
 * This class attempts to create secure connections using common TLS
 * extensions and SSL deflate compression. Should that fail, the
 * connection will be retried with SSLv3 only.
 */
public abstract class OkHttpsConnection extends OkHttpConnection {

    private static HostnameVerifier defaultHostnameVerifier
            = HttpsURLConnection.getDefaultHostnameVerifier();

    private static SSLSocketFactory defaultSSLSocketFactory = (SSLSocketFactory) SSLSocketFactory
            .getDefault();

    /**
     * Sets the default hostname verifier to be used by new instances.
     *
     * @param v
     *            the new default hostname verifier
     * @throws IllegalArgumentException
     *             if the specified verifier is {@code null}.
     */
    public static void setDefaultHostnameVerifier(HostnameVerifier v) {
        if (v == null) {
            throw new IllegalArgumentException("HostnameVerifier is null");
        }
        defaultHostnameVerifier = v;
    }

    /**
     * Returns the default hostname verifier.
     *
     * @return the default hostname verifier.
     */
    public static HostnameVerifier getDefaultHostnameVerifier() {
        return defaultHostnameVerifier;
    }

    /**
     * Sets the default SSL socket factory to be used by new instances.
     *
     * @param sf
     *            the new default SSL socket factory.
     * @throws IllegalArgumentException
     *             if the specified socket factory is {@code null}.
     */
    public static void setDefaultSSLSocketFactory(SSLSocketFactory sf) {
        if (sf == null) {
            throw new IllegalArgumentException("SSLSocketFactory is null");
        }
        defaultSSLSocketFactory = sf;
    }

    /**
     * Returns the default SSL socket factory for new instances.
     *
     * @return the default SSL socket factory for new instances.
     */
    public static SSLSocketFactory getDefaultSSLSocketFactory() {
        return defaultSSLSocketFactory;
    }

    /**
     * The host name verifier used by this connection. It is initialized from
     * the default hostname verifier
     * {@link #setDefaultHostnameVerifier(javax.net.ssl.HostnameVerifier)} or
     * {@link #getDefaultHostnameVerifier()}.
     */
    protected HostnameVerifier hostnameVerifier;

    private SSLSocketFactory sslSocketFactory;

    /**
     * Creates a new {@code HttpsURLConnection} with the specified {@code URL}.
     *
     * @param url
     *            the {@code URL} to connect to.
     */
    protected OkHttpsConnection(URL url) {
        super(url);
        hostnameVerifier = defaultHostnameVerifier;
        sslSocketFactory = defaultSSLSocketFactory;
    }

    /**
     * Returns the name of the cipher suite negotiated during the SSL handshake.
     *
     * @return the name of the cipher suite negotiated during the SSL handshake.
     * @throws IllegalStateException
     *             if no connection has been established yet.
     */
    public abstract String getCipherSuite();

    /**
     * Returns the list of local certificates used during the handshake. These
     * certificates were sent to the peer.
     *
     * @return Returns the list of certificates used during the handshake with
     *         the local identity certificate followed by CAs, or {@code null}
     *         if no certificates were used during the handshake.
     * @throws IllegalStateException
     *             if no connection has been established yet.
     */
    public abstract Certificate[] getLocalCertificates();

    /**
     * Return the list of certificates identifying the peer during the
     * handshake.
     *
     * @return the list of certificates identifying the peer with the peer's
     *         identity certificate followed by CAs.
     * @throws javax.net.ssl.SSLPeerUnverifiedException
     *             if the identity of the peer has not been verified..
     * @throws IllegalStateException
     *             if no connection has been established yet.
     */
    public abstract Certificate[] getServerCertificates() throws SSLPeerUnverifiedException;

    /**
     * Returns the {@code Principal} identifying the peer.
     *
     * @return the {@code Principal} identifying the peer.
     * @throws javax.net.ssl.SSLPeerUnverifiedException
     *             if the identity of the peer has not been verified.
     * @throws IllegalStateException
     *             if no connection has been established yet.
     */
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        Certificate[] certs = getServerCertificates();
        if (certs == null || certs.length == 0 || (!(certs[0] instanceof X509Certificate))) {
            throw new SSLPeerUnverifiedException("No server's end-entity certificate");
        }
        return ((X509Certificate) certs[0]).getSubjectX500Principal();
    }

    /**
     * Returns the {@code Principal} used to identify the local host during the handshake.
     *
     * @return the {@code Principal} used to identify the local host during the handshake, or
     *         {@code null} if none was used.
     * @throws IllegalStateException
     *             if no connection has been established yet.
     */
    public Principal getLocalPrincipal() {
        Certificate[] certs = getLocalCertificates();
        if (certs == null || certs.length == 0 || (!(certs[0] instanceof X509Certificate))) {
            return null;
        }
        return ((X509Certificate) certs[0]).getSubjectX500Principal();
    }

    /**
     * Sets the hostname verifier for this instance.
     *
     * @param v
     *            the hostname verifier for this instance.
     * @throws IllegalArgumentException
     *             if the specified verifier is {@code null}.
     */
    public void setHostnameVerifier(HostnameVerifier v) {
        if (v == null) {
            throw new IllegalArgumentException("HostnameVerifier is null");
        }
        hostnameVerifier = v;
    }

    /**
     * Returns the hostname verifier used by this instance.
     */
    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    /**
     * Sets the SSL socket factory for this instance.
     *
     * @param sf
     *            the SSL socket factory to be used by this instance.
     * @throws IllegalArgumentException
     *             if the specified socket factory is {@code null}.
     */
    public void setSSLSocketFactory(SSLSocketFactory sf) {
        if (sf == null) {
            throw new IllegalArgumentException("SSLSocketFactory is null");
        }
        sslSocketFactory = sf;
    }

    /**
     * Returns the SSL socket factory used by this instance.
     */
    public SSLSocketFactory getSSLSocketFactory() {
        return sslSocketFactory;
    }

}
