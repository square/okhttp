package okhttp3.okwebserver;

import okhttp3.webserver.ClientRequest;
import okhttp3.webserver.Dispatcher;
import okhttp3.webserver.ServerResponse;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A dispatcher that responds with enqueued responses or 200 if no responses are enqueued.
 */
public class FakeDispatcher extends Dispatcher {

  private final BlockingQueue<ServerResponse> responsesQueue = new LinkedBlockingQueue<>();

  @Override
  public ServerResponse dispatch(ClientRequest clientRequest) throws InterruptedException {
    ServerResponse response = responsesQueue.poll();
    return response != null ? response : new ServerResponse();
  }

  public void enqueueResponse(ServerResponse response) {
    responsesQueue.offer(response);
  }
}
