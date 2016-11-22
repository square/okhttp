package okhttp3.internal.http;

import okio.AsyncTimeout;
import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * The Okio timeout watchdog will call {@link #timeout} if the timeout is reached. In that case
 * we close the stream (asynchronously) which will notify the waiting thread
 */
public abstract class HttpRequestDeadline extends AsyncTimeout {
  @Override protected IOException newTimeoutException(IOException cause) {
    SocketTimeoutException socketTimeoutException = new SocketTimeoutException("Request deadline");
    if (cause != null) {
      socketTimeoutException.initCause(cause);
    }
    return socketTimeoutException;
  }

  public void exitAndThrowIfDeadlineIsReached() throws IOException {
    if (exit()) throw newTimeoutException(null /* cause */);
  }
}
