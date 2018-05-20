package okhttp3;

import java.io.IOException;

/**
 * https://datatracker.ietf.org/doc/draft-ietf-httpbis-h2-websockets/?include_text=1
 */
public interface UpgradeHandler<T> {
  T connect(OkHttpClient client);

  boolean supportsHttp11();

  boolean supportsHttp2();

  /**
   * https://www.iana.org/assignments/http-upgrade-tokens/http-upgrade-tokens.xhtml
   */
  String connectProtocol();

  Request addUpgradeHeaders(Request upgradeRequest);

  void failConnect(Response response, IOException e) throws IOException;
}
