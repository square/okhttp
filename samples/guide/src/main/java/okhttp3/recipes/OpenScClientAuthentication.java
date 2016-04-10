package okhttp3.recipes;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import sun.security.pkcs11.SunPKCS11;

/**
 * Client Authentication using a Smart Card
 *
 * OpenSC can be installed from https://github.com/OpenSC/OpenSC/wiki
 */
public class OpenScClientAuthentication {
  String run(String url) throws Exception {
    char[] password = System.console().readPassword("smartcard password: ");
    KeyManager[] keyManagers = getKeyManagers(password);

    X509TrustManager trustManager =
        CustomTrust.trustManagerForCertificates(new FileInputStream("testserver/cert.pem"));

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers, new TrustManager[] { trustManager }, null);
    SSLSocketFactory socketFactory = context.getSocketFactory();

    OkHttpClient client = new OkHttpClient.Builder().sslSocketFactory(socketFactory, trustManager).build();

    Request request = new Request.Builder()
        .url(url)
        .build();

    Response response = client.newCall(request).execute();
    return response.body().string();
  }

  public static void main(String[] args) throws Exception {
    OpenScClientAuthentication example = new OpenScClientAuthentication();

    /*
     * openssl dhparam -out dhparam.pem 2048
     * openssl req -x509 -sha256 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365
     *          -nodes -subj '/O=Company/OU=Department/CN=localhost'
     *
     * openssl s_server -verify 20 -key key.pem -cert cert.pem -accept 44330 -no_ssl3
     *         -dhparam dhparam.pem -www
     */

    String response = example.run("https://localhost:44330");
    System.out.println(response);
  }

  public static KeyManager[] getKeyManagers(char[] password)
      throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException,
      UnrecoverableKeyException {
    String config = "name=OpenSC\nlibrary=/Library/OpenSC/lib/opensc-pkcs11.so\n";
    SunPKCS11 pkcs11 =
        new SunPKCS11(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
    Security.addProvider(pkcs11);

    KeyStore keystore = KeyStore.getInstance("PKCS11", pkcs11);
    keystore.load(null, password);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance("NewSunX509");
    kmf.init(keystore, null);

    return kmf.getKeyManagers();
  }
}
