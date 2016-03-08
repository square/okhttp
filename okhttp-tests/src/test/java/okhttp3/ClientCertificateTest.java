package okhttp3;

import okhttp3.*;
import okhttp3.internal.HeldCertificate;
import okhttp3.internal.SslContextBuilder;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

public class ClientCertificateTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private MockWebServer mockWebServer = new MockWebServer();

    @Test
    public void clientCertificateInDefaultContext() throws IOException, GeneralSecurityException {
        char[] password = "password".toCharArray();
        KeyStore keyStore = SslContextBuilder.newEmptyKeyStore(password);

        // TODO Modify okhttp3.internal.SslContextBuilder to expose keyStore
        SSLContext sslContext = buildSslContext(password, keyStore);
        File file = writeKeyStore(password, keyStore);

        mockWebServer.useHttps(sslContext.getSocketFactory(), false);
        mockWebServer.start();
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.REQUIRE_CLIENT_CERTIFICATE));

        System.setProperty("javax.net.ssl.trustStore", file.getAbsolutePath());
        System.setProperty("javax.net.ssl.keyStore", file.getAbsolutePath());
        System.setProperty("javax.net.ssl.keyStoreType", "jks");
        System.setProperty("javax.net.ssl.keyStorePassword", "password");

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .dns(resolvesToIpv4Localhost()) // FIXME Server doesn't bind to ::1, but DNS resolves to ::1
                // Uncomment for test to pass!
                //.sslSocketFactory(SSLContext.getDefault().getSocketFactory())
                .build();

        okHttpClient.newCall(new Request.Builder().get().url(mockWebServer.url("/")).build()).execute();
    }

    private File writeKeyStore(char[] password, KeyStore keyStore) throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
        File file = tmp.newFile();
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        keyStore.store(fileOutputStream, password);
        return file;
    }

    // TODO Modify SslContextBuilder to expose KeyStore, somehow
    private SSLContext buildSslContext(char[] password, KeyStore keyStore) throws GeneralSecurityException, UnknownHostException {
        HeldCertificate heldCertificate = new HeldCertificate.Builder()
                .serialNumber("1")
                .commonName(InetAddress.getByName("localhost").getHostName())
                .build();

        HeldCertificate[] chain = new HeldCertificate[] { heldCertificate };
        List<X509Certificate> trustedCertificates = Arrays.asList(heldCertificate.certificate);

        if (chain != null) {
            Certificate[] certificates = new Certificate[chain.length];
            for (int i = 0; i < chain.length; i++) {
                certificates[i] = chain[i].certificate;
            }
            keyStore.setKeyEntry("private", chain[0].keyPair.getPrivate(), password, certificates);
        }

        for (int i = 0; i < trustedCertificates.size(); i++) {
            keyStore.setCertificateEntry("cert_" + i, trustedCertificates.get(i));
        }


        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);
        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(),
                new SecureRandom());
        return sslContext;
    }

    private Dns resolvesToIpv4Localhost() {
        return new Dns() {
            @Override
            public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                return Arrays.asList(InetAddress.getByName("127.0.0.1"));
            }
        };
    }

}
