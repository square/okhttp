package okhttp3.internal.platform;

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Protocol;
import okhttp3.internal.Util;
import org.conscrypt.OpenSSLProvider;
import org.conscrypt.OpenSSLSocketImpl;

// TODO support SSLClientSessionCache
// TODO support TLS 1.3 (debugging needed)
// TODO support 0-RTT (TLS 1.3)

/**
 * Platform using Conscrypt (conscrypt.org) if system property
 * okhttp.platform is set to conscrypt.
 *
 * Returns org.conscrypt:conscrypt-openjdk-uber on the classpath.
 */
public class ConscryptPlatform extends Platform {
  public ConscryptPlatform() {
  }

  public Provider getProvider() {
    return new OpenSSLProvider();
  }

  @Override public X509TrustManager trustManager(SSLSocketFactory sslSocketFactory) {
    if (sslSocketFactory.getClass().getName().endsWith("OpenSSLSocketFactoryImpl")) {
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
    } else {
      return super.trustManager(sslSocketFactory);
    }
  }

  @Override public void configureTlsExtensions(
      SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
    if (sslSocket instanceof OpenSSLSocketImpl) {
      OpenSSLSocketImpl i = (OpenSSLSocketImpl) sslSocket;

      // Enable SNI and session tickets.
      if (hostname != null) {
        i.setUseSessionTickets(true);
        i.setHostname(hostname);
      }

      // Enable ALPN.
      List<String> names = Platform.alpnProtocolNames(protocols);
      i.setAlpnProtocols(names.toArray(new String[0]));
    } else {
      super.configureTlsExtensions(sslSocket, hostname, protocols);
    }
  }

  @Override public String getSelectedProtocol(SSLSocket sslSocket) {
    if (sslSocket instanceof OpenSSLSocketImpl) {
      OpenSSLSocketImpl i = (OpenSSLSocketImpl) sslSocket;

      byte[] alpnResult = i.getAlpnSelectedProtocol();

      return alpnResult != null ? new String(alpnResult, Util.UTF_8) : null;
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
}
