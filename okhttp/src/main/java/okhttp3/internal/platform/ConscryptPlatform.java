package okhttp3.internal.platform;

//import org.conscrypt.

import java.security.Provider;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Protocol;
import okhttp3.internal.Util;
import org.conscrypt.OpenSSLProvider;
import org.conscrypt.OpenSSLSocketImpl;

public class ConscryptPlatform extends Platform {
  public ConscryptPlatform() {
  }

  @Override public Provider getProvider() {
    return new OpenSSLProvider();
  }

  @Override public X509TrustManager trustManager(SSLSocketFactory sslSocketFactory) {
    throw new UnsupportedOperationException(
        "clientBuilder.sslSocketFactory(SSLSocketFactory) not supported on Conscrypt");
  }

  @Override public void configureTlsExtensions(
      SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
    OpenSSLSocketImpl i = (OpenSSLSocketImpl) sslSocket;

    // Enable SNI and session tickets.
    if (hostname != null) {
      i.setUseSessionTickets(true);
      i.setHostname(hostname);
    }

    // Enable ALPN.
    List<String> names = Platform.alpnProtocolNames(protocols);
    i.setAlpnProtocols(names.toArray(new String[0]));
  }

  @Override public String getSelectedProtocol(SSLSocket socket) {
    OpenSSLSocketImpl i = (OpenSSLSocketImpl) socket;

    byte[] alpnResult = i.getAlpnSelectedProtocol();

    return alpnResult != null ? new String(alpnResult, Util.UTF_8) : null;
  }
}
