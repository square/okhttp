package okhttp3.internal.tls;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Combines an SSLSocketFactory and trustManager, a pairing enough for OkHttp to create a
 * secure connection e.g. with custom certificate checks.
 */
public class SslClient {
  public final SSLContext sslContext;

  public final SSLSocketFactory socketFactory;

  public final X509TrustManager trustManager;

  public SslClient(SSLContext sslContext, X509TrustManager trustManager) {
    this.sslContext = sslContext;
    this.socketFactory = sslContext.getSocketFactory();
    this.trustManager = trustManager;
  }

  public static X509TrustManager systemDefaultTrustManager() {
    try {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
          TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init((KeyStore) null);
      TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
      if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
        throw new IllegalStateException("Unexpected default trust managers:"
            + Arrays.toString(trustManagers));
      }
      return (X509TrustManager) trustManagers[0];
    } catch (GeneralSecurityException e) {
      throw new AssertionError(); // The system has no TLS. Just give up.
    }
  }

  public static SSLContext systemDefaultSSLContext(X509TrustManager trustManager) {
    try {
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, new TrustManager[] { trustManager }, null);
      return sslContext;
    } catch (GeneralSecurityException e) {
      throw new AssertionError(); // The system has no TLS. Just give up.
    }
  }

  public static SslClient systemDefault() {
    X509TrustManager trustManager = systemDefaultTrustManager();
    SSLContext sslContext = systemDefaultSSLContext(trustManager);

    return new SslClient(sslContext, trustManager);
  }

  public static class Builder {
    private List<X509Certificate> chainCertificates = new ArrayList<>();
    private KeyPair keyPair = null;
    private List<X509Certificate> certificates = new ArrayList<>();

    public SslClient build() {
      try {
        // Put the certificate in a key store.
        char[] password = "password".toCharArray();
        KeyStore keyStore = newEmptyKeyStore(password);

        if (keyPair != null) {
          Certificate[] certificates =
              chainCertificates.toArray(new Certificate[chainCertificates.size()]);
          keyStore.setKeyEntry("private", keyPair.getPrivate(), password, certificates);
        }

        for (int i = 0; i < certificates.size(); i++) {
          keyStore.setCertificateEntry("cert_" + i, certificates.get(i));
        }

        // Wrap it up in an SSL context.
        KeyManagerFactory keyManagerFactory =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);
        TrustManagerFactory trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

        if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
          throw new IllegalStateException("Unexpected default trust managers:"
              + Arrays.toString(trustManagers));
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagers,
            new SecureRandom());

        return new SslClient(sslContext, (X509TrustManager) trustManagers[0]);
      } catch (GeneralSecurityException gse) {
        throw new AssertionError(gse);
      }
    }

    private KeyStore newEmptyKeyStore(char[] password) throws GeneralSecurityException {
      try {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        InputStream in = null; // By convention, 'null' creates an empty key store.
        keyStore.load(in, password);
        return keyStore;
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    public SslClient.Builder certificateChain(KeyPair keyPair, X509Certificate keyCert,
        X509Certificate... certificates) {
      this.keyPair = keyPair;
      this.certificates = new ArrayList<>();
      this.certificates.add(keyCert);
      this.certificates.addAll(Arrays.asList(certificates));

      return this;
    }

    public Builder addTrustedCertificate(X509Certificate certificate) {
      this.certificates.add(certificate);

      return this;
    }
  }
}
