package okhttp3.internal.ws;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Collections;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.connection.StreamAllocation;

import static okhttp3.internal.Util.closeQuietly;

public class Http11Upgrade {
  private static final List<Protocol> ONLY_HTTP1 = Collections.singletonList(Protocol.HTTP_1_1);

  void checkResponse(Response response, UpgradeHandler<?> upgradeHandler) throws ProtocolException {
    if (response.code() != 101) {
      throw new ProtocolException("Expected HTTP 101 response but was '"
          + response.code()
          + " "
          + response.message()
          + "'");
    }

    String headerConnection = response.header("Connection");
    if (!"Upgrade".equalsIgnoreCase(headerConnection)) {
      throw new ProtocolException(
          "Expected 'Connection' header value 'Upgrade' but was '" + headerConnection + "'");
    }

    String headerUpgrade = response.header("Upgrade");
    String upgradeProtocolToken = upgradeHandler.upgradeProtocolToken();
    if (!upgradeProtocolToken.equalsIgnoreCase(headerUpgrade)) {
      throw new ProtocolException("Expected 'Upgrade' header value '"
          + upgradeProtocolToken
          + "' but was '"
          + headerUpgrade
          + "'");
    }

    upgradeHandler.checkResponse(response);
  }

  public <T> T connect(OkHttpClient client, Request request,
      final UpgradeHandler<T> upgradeHandler) {
    if (!upgradeHandler.supportsProtocol(Protocol.HTTP_1_1)) {
      throw new IllegalStateException();
    }

    client = client.newBuilder().eventListener(EventListener.NONE).protocols(ONLY_HTTP1).build();
    request = upgradeHandler.addUpgradeHeaders(request).newBuilder()
        .header("Upgrade", upgradeHandler.upgradeProtocolToken())
        .header("Connection", "Upgrade")
        .build();
    Call call = Internal.instance.newWebSocketCall(client, request);
    call.enqueue(new Callback() {
      @Override public void onResponse(Call call, Response response) {
        try {
          checkResponse(response, upgradeHandler);
        } catch (ProtocolException e) {
          closeQuietly(response);
          return;
        }

        // Promote the HTTP streams into (web socket) streams.
        StreamAllocation streamAllocation = Internal.instance.streamAllocation(call);
        streamAllocation.noNewStreams(); // Prevent connection pooling!
        Streams streams = streamAllocation.connection().newStreams(streamAllocation);

        // Process all (web socket) messages.
        try {
          streamAllocation.connection().socket().setSoTimeout(0);
          upgradeHandler.process(streams, response);
        } catch (Exception e) {
          upgradeHandler.failConnect(response, e);
        }
      }

      @Override public void onFailure(Call call, IOException e) {
        upgradeHandler.failConnect(null, e);
      }
    });

    return upgradeHandler.result();
  }
}
