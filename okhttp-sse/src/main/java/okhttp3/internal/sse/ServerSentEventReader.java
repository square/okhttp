package okhttp3.internal.sse;

import java.io.IOException;
import javax.annotation.Nullable;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;

public final class ServerSentEventReader {
  private static final ByteString CRLF = ByteString.encodeUtf8("\r\n");
  private static final ByteString DATA = ByteString.encodeUtf8("data");
  private static final ByteString ID = ByteString.encodeUtf8("id");
  private static final ByteString EVENT = ByteString.encodeUtf8("event");
  private static final ByteString RETRY = ByteString.encodeUtf8("retry");

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
    Buffer data = new Buffer();

    while (true) {
      long lineEnd = source.indexOfElement(CRLF);
      if (lineEnd == -1L) {
        return false;
      }

      switch (source.buffer().getByte(0)) {
        case '\r':
        case '\n':
          completeEvent(id, type, data);
          return true;

        case 'd':
          if (isKey(DATA)) {
            parseData(data, lineEnd);
            continue;
          }
          break;

        case 'e':
          if (isKey(EVENT)) {
            type = parseEvent(lineEnd);
            continue;
          }
          break;

        case 'i':
          if (isKey(ID)) {
            id = parseId(lineEnd);
            continue;
          }
          break;

        case 'r':
          if (isKey(RETRY)) {
            parseRetry(lineEnd);
            continue;
          }
          break;
      }

      source.skip(lineEnd);
      skipCrAndOrLf();
    }
  }

  private void completeEvent(String id, String type, Buffer data) throws IOException {
    skipCrAndOrLf();

    if (data.size() != 0L) {
      lastId = id;
      data.skip(1L); // Leading newline.
      callback.onEvent(id, type, data.readUtf8());
    }
  }

  private void parseData(Buffer data, long end) throws IOException {
    data.writeByte('\n');
    end -= skipNameAndDivider(4L);
    source.readFully(data, end);
    skipCrAndOrLf();
  }

  private String parseEvent(long end) throws IOException {
    String type = null;
    end -= skipNameAndDivider(5L);
    if (end != 0L) {
      type = source.readUtf8(end);
    }
    skipCrAndOrLf();
    return type;
  }

  private String parseId(long end) throws IOException {
    String id;
    end -= skipNameAndDivider(2L);
    if (end != 0L) {
      id = source.readUtf8(end);
    } else {
      id = null;
    }
    skipCrAndOrLf();
    return id;
  }

  private void parseRetry(long end) throws IOException {
    end -= skipNameAndDivider(5L);
    String retryString = source.readUtf8(end);
    long retryMs = -1L;
    try {
      retryMs = Long.parseLong(retryString);
    } catch (NumberFormatException ignored) {
    }
    if (retryMs != -1L) {
      callback.onRetryChange(retryMs);
    }
    skipCrAndOrLf();
  }

  /**
   * Returns true if the first bytes of {@link #source} are {@code key} followed by a colon or
   * a newline.
   */
  private boolean isKey(ByteString key) throws IOException {
    if (source.rangeEquals(0, key)) {
      byte nextByte = source.buffer().getByte(key.size());
      return nextByte == ':'
          || nextByte == '\r'
          || nextByte == '\n';
    }
    return false;
  }

  /** Consumes {@code \r}, {@code \r\n}, or {@code \n} from {@link #source}. */
  private void skipCrAndOrLf() throws IOException {
    if ((source.readByte() & 0xff) == '\r'
        && source.request(1)
        && source.buffer().getByte(0) == '\n') {
      source.skip(1);
    }
  }

  /**
   * Consumes the field name of the specified length and the optional colon and its optional
   * trailing space. Returns the number of bytes skipped.
   */
  private long skipNameAndDivider(long length) throws IOException {
    source.skip(length);

    if (source.buffer().getByte(0) == ':') {
      source.skip(1L);
      length++;

      if (source.buffer().getByte(0) == ' ') {
        source.skip(1);
        length++;
      }
    }

    return length;
  }
}
