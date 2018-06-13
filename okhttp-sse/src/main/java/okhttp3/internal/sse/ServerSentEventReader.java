package okhttp3.internal.sse;

import java.io.IOException;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.ByteString;

public final class ServerSentEventReader {
  private static final ByteString CRLF = ByteString.encodeUtf8("\r\n");
  private static final byte COLON = (byte) ':';

  public interface Callback {
    void onEvent(@Nullable String id, @Nullable String type, String data);
    void onRetryChange(long timeMs);
  }

  private final BufferedSource source;
  private final Callback callback;

  private String lastId = null;

  public ServerSentEventReader(BufferedSource source, Callback callback) {
    if (source == null) throw new NullPointerException("source == null");
    if (callback == null) throw new NullPointerException("callback == null");
    this.source = source;
    this.callback = callback;
  }

  /**
   * Process the next event. This will result in a single call to {@link Callback#onEvent}
   * <em>unless</em> the data section was empty. Any number of calls to
   * {@link Callback#onRetryChange} may occur while processing an event.
   *
   * @return false when EOF is reached
   */
  boolean processNextEvent() throws IOException {
    String id = lastId;
    String type = null;
    StringBuilder data = null;

    while (true) {
      long crlf = source.indexOfElement(CRLF);
      if (crlf == -1L) {
        return false;
      }

      if (crlf == 0L) {
        skipCrAndOrLf();

        if (data != null) {
          lastId = id;
          callback.onEvent(id, type, data.toString());
        }

        return true;
      }

      long colon = source.indexOf(COLON, 0, crlf);
      if (colon == 0L) {
        // Comment line. Skip in its entirety.
        source.skip(crlf);
        skipCrAndOrLf();
        continue;
      }

      String fieldName;
      String fieldValue;
      if (colon == -1L) {
        fieldName = source.readUtf8(crlf);
        fieldValue = "";
      } else {
        fieldName = source.readUtf8(colon);
        crlf -= colon;

        source.skip(1L);
        crlf--;

        // No need to request(1) before checking for the optional space because we've buffered
        // enough to see the line ending which is at worst the next byte.
        if (source.buffer().getByte(0) == ' ') {
          source.skip(1L);
          crlf--;
        }

        fieldValue = source.readUtf8(crlf);
      }
      skipCrAndOrLf();

      switch (fieldName) {
        case "data":
          if (data == null) {
            data = new StringBuilder();
          } else {
            data.append('\n');
          }
          data.append(fieldValue);
          break;

        case "id":
          if (fieldValue.isEmpty()) {
            fieldValue = null;
          }
          id = fieldValue;
          break;

        case "event":
          if (fieldValue.isEmpty()) {
            fieldValue = null;
          }
          type = fieldValue;
          break;

        case "retry":
          long timeMs;
          try {
            timeMs = Long.parseLong(fieldValue);
          } catch (NumberFormatException ignored) {
            break;
          }
          callback.onRetryChange(timeMs);
          break;

        default:
          source.skip(crlf);
          skipCrAndOrLf();
          break;
      }
    }
  }

  /** Consumes {@code \r}, {@code \r\n}, or {@code \n} from {@link #source}. */
  private void skipCrAndOrLf() throws IOException {
    if ((source.readByte() & 0xff) == '\r'
        && source.request(1)
        && source.buffer().getByte(0) == '\n') {
      source.skip(1);
    }
  }
}
