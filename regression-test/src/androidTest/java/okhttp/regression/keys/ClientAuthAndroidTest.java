/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp.regression.keys;

import android.security.KeyChain;
import android.security.KeyChainException;
import androidx.core.app.ComponentActivity;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * OkHttp.
 * <p>
 * https://square.github.io/okhttp/
 */
@RunWith(AndroidJUnit4.class)
public class ClientAuthAndroidTest {
//    @Rule
//    public ActivityScenarioRule<MainActivity> rule = new ActivityScenarioRule<>(MainActivity.class);

    class FixedKeyManager implements X509KeyManager {
        private final PrivateKey pk;
        private final X509Certificate[] chain;

        public FixedKeyManager(PrivateKey pk, X509Certificate... chain) {
            this.pk = pk;
            this.chain = chain;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{"mykey"};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return "mykey";
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            throw new UnsupportedOperationException();
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return chain;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return pk;
        }
    }

    @Test
    public void generateCertificate() throws IOException, NoSuchAlgorithmException, KeyManagementException {
        HandshakeCertificates m = new HandshakeCertificates.Builder()
                .addPlatformTrustedCertificates()
                .build();

//        java.lang.SecurityException: exception: java.security.NoSuchAlgorithmException: The BC provider no longer
//        provides an implementation for Signature.SHA256withECDSA.  Please see
//        https://android-developers.googleblog.com/2018/03/cryptography-changes-in-android-p.html for more details.
//        at org.bouncycastle.x509.X509V3CertificateGenerator.generateX509Certificate(Unknown Source:25)
//        at org.bouncycastle.x509.X509V3CertificateGenerator.generateX509Certificate(Unknown Source:3)
        HeldCertificate localhost = new HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName(InetAddress.getByName("localhost").getCanonicalHostName())
                .build();

        FixedKeyManager keyManager = new FixedKeyManager(localhost.keyPair().getPrivate(), localhost.certificate());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(new KeyManager[]{keyManager}, new TrustManager[]{m.trustManager()}, null);

        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), m.trustManager())
                .build();

        Request request = new Request.Builder()
                .url("https://server.cryptomix.com/secure/")
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());

            System.out.println(response.body().string());
        }
    }

    @Test
    public void keyStoreCertificate() throws IOException, NoSuchAlgorithmException, KeyManagementException, KeyStoreException, KeyChainException, InterruptedException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null);
        X509TrustManager trustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];

        CountDownLatch latch = new CountDownLatch(1);

        String[] selectedAlias = new String[1];
        KeyChain.choosePrivateKeyAlias(null, // TODO launch with an activity
                alias -> {
                    selectedAlias[0] = alias;
                    latch.countDown();
                },
                new String[]{"RSA", "DSA"},  // List of acceptable key types. null for any
                null,  // issuer, null for any
                "server.cryptomix.com",  // host name of server requesting the cert, null if unavailable
                443,  // port of server requesting the cert, -1 if unavailable
                "mykey"); // alias to preselect, null if unavailable

        PrivateKey pk = KeyChain.getPrivateKey(InstrumentationRegistry.getInstrumentation().getTargetContext(), "mykey");
        X509Certificate[] chain = KeyChain.getCertificateChain(InstrumentationRegistry.getInstrumentation().getTargetContext(), "mykey");

        assertNotNull(pk);
        assertNotNull(chain);

        FixedKeyManager keyManager = new FixedKeyManager(pk, chain);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(new KeyManager[]{keyManager}, new TrustManager[]{trustManager}, null);

        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .build();

        Request request = new Request.Builder()
                .url("https://server.cryptomix.com/secure/")
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertEquals(200, response.code());

            System.out.println(response.body().string());
        }
    }
}
