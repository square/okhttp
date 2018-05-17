package okhttp3;

import java.io.Closeable;
import okio.BufferedSink;
import okio.BufferedSource;

public abstract class Streams implements Closeable {
    public final boolean client;
    public final BufferedSource source;
    public final BufferedSink sink;

    public Streams(boolean client, BufferedSource source, BufferedSink sink) {
      this.client = client;
      this.source = source;
      this.sink = sink;
    }
  }