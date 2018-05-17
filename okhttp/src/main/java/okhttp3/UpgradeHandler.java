package okhttp3;

import java.io.IOException;

/**
 * https://datatracker.ietf.org/doc/draft-ietf-httpbis-h2-websockets/?include_text=1
 */
public interface UpgradeHandler<T> {
  boolean supportsHttp11();

  boolean supportsHttp2();

  String connectProtocol();

  Request addUpgradeHeaders(Request upgradeRequest);

  T complete(Response response, Streams streams) throws IOException;

  void failConnect(Response response, IOException e) throws IOException;
}
