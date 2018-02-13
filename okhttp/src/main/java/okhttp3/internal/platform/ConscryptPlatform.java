package okhttp3.internal.platform;

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.List;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Protocol;
import org.conscrypt.Conscrypt;
import org.conscrypt.OpenSSLProvider;

/**
 * Platform using Conscrypt (conscrypt.org) if installed as the first Security Provider.
 *
 * Requires org.conscrypt:conscrypt-openjdk-uber on the classpath.
 */
public class ConscryptPlatform extends Platform {
  private ConscryptPlatform() {
  }

  public static boolean isPreferredPlatform() {
    // mainly to allow tests to run cleanly
    if ("conscrypt".equals(System.getProperty("okhttp.platform"))) {
      return true;
    }

    // check if Provider manually installed
    String preferredProvider = Security.getProviders()[0].getName();
    return "Conscrypt".equals(preferredProvider);
  }

  private Provider getProvider() {
    return new OpenSSLProvider();
  }

  @Override public X509TrustManager trustManager(SSLSocketFactory sslSocketFactory) {
    if (!Conscrypt.isConscrypt(sslSocketFactory)) {
      return super.trustManager(sslSocketFactory);
    }

    try {
      // org.conscrypt.SSLParametersImpl
      Object sp =
          readFieldOrNull(sslSocketFactory, Object.class, "sslParameters");

      if (sp != null) {
        return readFieldOrNull(sp, X509TrustManager.class, "x509TrustManager");
      }

      return null;
    } catch (Exception e) {
      throw new UnsupportedOperationException(
          "clientBuilder.sslSocketFactory(SSLSocketFactory) not supported on Conscrypt", e);
    }
  }

  @Override public void configureTlsExtensions(
      SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
    if (Conscrypt.isConscrypt(sslSocket)) {
      // Enable SNI and session tickets.
      if (hostname != null) {
        Conscrypt.setUseSessionTickets(sslSocket, true);
        Conscrypt.setHostname(sslSocket, hostname);
      }

      // Enable ALPN.
      List<String> names = Platform.alpnProtocolNames(protocols);
      Conscrypt.setApplicationProtocols(sslSocket, names.toArray(new String[0]));
    } else {
      super.configureTlsExtensions(sslSocket, hostname, protocols);
    }
  }

  @Override public @Nullable String getSelectedProtocol(SSLSocket sslSocket) {
    if (Conscrypt.isConscrypt(sslSocket)) {
      return Conscrypt.getApplicationProtocol(sslSocket);
    } else {
      return super.getSelectedProtocol(sslSocket);
    }
  }

  @Override public SSLContext getSSLContext() {
    try {
      return SSLContext.getInstance("TLS", getProvider());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("No TLS provider", e);
    }
  }

  public static Platform buildIfSupported() {
    try {
      // trigger early exception over a fatal error
      Class.forName("org.conscrypt.ConscryptEngineSocket");

      if (!Conscrypt.isAvailable()) {
        return null;
      }

      Conscrypt.setUseEngineSocketByDefault(true);
      return new ConscryptPlatform();
    } catch (ClassNotFoundException e) {
      return null;
    }
  }
}
